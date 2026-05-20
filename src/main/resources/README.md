# HM-Dianping 大众点评 O2O 社区

基于 Spring Boot 构建的类大众点评 O2O 本地生活服务平台，涵盖商户探店、优惠券秒杀、点赞互动、好友关注等核心功能，是一个高并发场景下的后端技术实践项目。

---

## 一、技术栈

| 层级 | 技术 | 版本 |
|---|---|---|
| 基础框架 | Spring Boot | 2.6.15 |
| 开发语言 | Java | 8 |
| 持久层 | MyBatis-Plus | 3.4.3 |
| 数据库 | MySQL | 5.x (Connector 5.1.47) |
| 缓存 | Redis (Lettuce + Redisson 3.13.6) | -- |
| 消息队列 | Apache RocketMQ | 2.2.3 (spring-boot-starter) |
| 分布式调度 | XXL-JOB | 2.4.1 |
| 流量控制 | Alibaba Sentinel | 2021.0.5.0 |
| 工具库 | Hutool | 5.7.17 |
| AOP | AspectJ (spring-boot-starter) | -- |
| JSON | Jackson (自动忽略 null 字段) | -- |
| 连接池 | Commons Pool 2 | -- |

---

## 二、项目架构

### 2.1 分层结构

```
src/main/java/com/hmdp/
├── controller/     # REST 接口层，处理 HTTP 请求与响应
├── service/        # 业务接口定义
│   └── impl/       # 业务逻辑实现，核心处理逻辑
├── mapper/         # MyBatis-Plus 数据访问层
├── entity/         # 实体类，与数据库表映射
├── dto/            # 数据传输对象（Result, UserDTO, ScrollResult 等）
├── config/         # Spring 配置（MVC 拦截器、Redisson、MyBatis 分页等）
├── utils/          # 工具类（Redis ID 生成器、用户上下文、常量等）
├── mq/             # RocketMQ 消息生产者、消费者、消息体定义
└── job/            # XXL-JOB 定时任务处理器
```

### 2.2 整体架构流程

```
客户端请求
  │
  ▼
Spring MVC Controller ───── 接口暴露，入参校验
  │
  ▼
Service / ServiceImpl ───── 业务逻辑编排
  │
  ├──► Redis ◄────────────── 缓存、分布式锁、Lua 原子操作、ZSet 排序
  │
  ├──► RocketMQ ◄─────────── 异步解耦（下单、支付、超时关单）
  │
  ├──► MySQL (+ MyBatis-Plus)  持久化存储
  │
  ├──► Redisson ◄─────────── 分布式锁（一人一单控制）
  │
  ├──► Sentinel ◄─────────── 接口限流，防止流量冲击
  │
  └──► XXL-JOB ◄──────────── 定时任务（点赞数据批量刷盘）
```

### 2.3 中间件职责

| 中间件 | 用途 |
|---|---|
| Redis | 商户缓存（TTL + 空值缓存）、秒杀库存预扣（Lua）、分布式锁、用户签到（BitMap）、点赞数据（ZSet + String）、附近商户（GEO）、用户笔记 Feed 流（ZSet） |
| RocketMQ | 秒杀异步下单、支付回调、超时关单（延迟消息） |
| Redisson | 分布式锁，保证一人一单 |
| Sentinel | 秒杀接口限流，触发时返回友好提示 |
| XXL-JOB | 定时批量同步 Redis 点赞数据到 MySQL（Write-Behind 模式） |

---

## 三、核心业务流程

### 3.1 优惠券秒杀

```
1. 用户 POST /voucher-order/seckill/{id} 发起抢券
2. Sentinel 拦截 → 超限则直接返回 "活动太火爆，请稍后再试"
3. 执行 seckill.lua（Redis 原子操作）：
   - 检查库存是否充足
   - 检查用户是否已下单（一人一单）
   - 扣减库存 + 记录用户
4. Lua 返回 0（成功）→ 生成全局唯一 orderId → 发送两条 RocketMQ 消息：
   - seckill-order-topic（普通消息）→ 消费者异步创建订单到 MySQL
   - order-timeout-topic（延迟消息, delayLevel=4）→ 用于超时关单
   5. 若 MQ 发送失败 → 回滚 Redis 预扣库存
   6. 立即返回 orderId 给前端
   ```
   
   ### 3.2 超时关单 & 支付闭环
   
   ```
   延迟消息到期 → OrderTimeoutListener 消费：
   ├── 订单 status=1（待支付）→ 更新为 status=4（已取消）
   │   ├── MySQL 库存 +1
   │   └── Redis 库存 +1，移除用户下单标记
   └── 订单 status≠1 → 跳过（已支付或其他终态）
   
   用户支付 → POST /api/pay/callback?orderId=xxx
   └── 直接更新订单 status=2（已支付），记录支付时间
   ```
   
   ### 3.3 点赞 Write-Behind
   
   ```
   用户点赞/取消点赞 → 纯 Redis 操作：
   ├── INCR/DECR  blog:like:count:{blogId}  （计数器）
   ├── ZADD/ZREM  blog:liked:{blogId}        （点赞用户集合）
   └── SADD       blog:like:changed           （脏数据标记）
   
   XXL-JOB 定时调度 likeSyncJob（建议每5分钟）：
   ├── SPOP blog:like:changed → 取出所有脏 blogId
   ├── GET blog:like:count:{id} → 读取最新点赞数
   └── 批量写入 MySQL（tb_blog + tb_blog_like_count，ON DUPLICATE KEY UPDATE）
   ```
   
   ### 3.4 商户查询（缓存穿透防护）
   
   ```
   查询商户 → 先查 Redis 缓存：
   ├── 命中 → 返回
   └── 未命中 → 加分布式锁 → 查 MySQL → 写入 Redis（含空值缓存防穿透）
   ```
   
   ### 3.5 用户 Feed 流（推模式）
   
   用户发布探店笔记 → 查询粉丝列表 → 推送 blogId 到每个粉丝的 Feed 收件箱（ZSet，按时间排序）
   
   ---
   
   ## 四、项目亮点
   
   ### 4.1 秒杀高并发优化
   
   - **Redis Lua 原子预扣**：库存校验 + 一人一单 + 库存扣减在一条 Lua 脚本中完成，Redis 单线程保证无竞态条件
   - **RocketMQ 异步解耦**：下单请求通过 MQ 异步落库，接口响应时间从「Lua + DB 写入」降为「仅 Lua + MQ 发送」，极大提升吞吐
   - **Sentinel 限流保护**：秒杀接口配置 Sentinel 流控规则，过载时快速失败，防止雪崩
   - **全局唯一 ID**：基于 Redis 自增的分布式 ID 生成器，支持高并发场景的唯一订单号
   
   ### 4.2 交易闭环完整性
   
   - **延迟消息超时关单**：下单后发送延迟 MQ 消息，到期自动检查订单状态并取消未支付订单，释放库存
   - **双写库存回滚**：关单时同时回滚 MySQL 和 Redis 库存，保证数据一致性
   - **支付回调幂等**：基于订单状态乐观锁（`eq("status", 1)`），防止重复支付或并发修改
   
   ### 4.3 点赞 Write-Behind 模式
   
   - **纯内存操作**：点赞/取消点赞仅操作 Redis，不触碰 MySQL，单次请求延迟降至毫秒级
   - **脏数据标记**：用 Redis Set 汇集发生变更的 blogId，定时任务按需批量同步
   - **XXL-JOB 批量刷盘**：SPOP 原子取出 + MyBatis 批量 SQL（CASE WHEN + ON DUPLICATE KEY UPDATE），一次同步数百条数据
   - **读写分离**：读路径直接取 Redis 最新计数值，保证用户看到实时点赞数
   
   ### 4.4 缓存设计
   
   - **多级 TTL**：热点商户长 TTL（30 分钟），空值短 TTL（2 分钟），平衡性能与内存
   - **缓存穿透防护**：查询不存在的数据时缓存空值，防止恶意请求穿透到数据库
   - **分布式锁写缓存**：缓存未命中时加 Redisson 锁，单线程重建缓存，防止缓存击穿
   - **Feed 推模式**：发布笔记时主动推送到粉丝收件箱，读时序简单高效
   
   ### 4.5 其他设计
   
   - **附近商户搜索**：基于 Redis GEO 数据结构，按地理位置排序返回周边商户
   - **用户签到**：基于 Redis BitMap 统计月度签到天数，内存占用极低
   - **共同关注**：基于 Redis Set 交集运算，快速计算两个用户的共同关注
   - **滚动分页**：Feed 流采用 ZSet 滚动分页（ScrollResult），避免数据重复和漏查
   
   ---
   
   ## 五、环境依赖
   
   | 组件 | 最低版本 | 说明 |
   |---|---|---|
   | JDK | 1.8+ | 开发语言环境 |
   | Maven | 3.6+ | 项目构建与管理 |
   | MySQL | 5.7+ | 数据库实例，端口 3306 |
   | Redis | 5.0+ | 缓存与分布式锁，端口 6379 |
   | RocketMQ | 4.9+ | NameServer (9876) + Broker |
   | XXL-JOB Admin | 2.4.x | 分布式调度中心 (8080) |
   | Sentinel Dashboard | 1.8.x | 可选，流控监控面板 (8080) |
   
   ---
   
   ## 六、部署教程
   
   ### 6.1 本地开发启动
   
   ```bash
   # 1. 克隆项目
   git clone <repository-url>
   cd hm-dianping
   
   # 2. 初始化数据库
   #    执行 src/main/resources/db/hmdp.sql 建表
   
   # 3. 启动中间件（Docker 示例）
   docker run -d -p 6379:6379 redis:7
   docker run -d -p 3306:3306 -e MYSQL_ROOT_PASSWORD=root mysql:5.7
   # RocketMQ、XXL-JOB Admin、Sentinel Dashboard 按需启动
   
   # 4. 修改配置（可选）
   #    编辑 src/main/resources/application.yaml
   #    修改数据库连接、Redis 地址、RocketMQ NameServer 地址等
   
   # 5. 启动项目
   mvn spring-boot:run
   # 或
   mvn clean package -DskipTests
   java -jar target/hm-dianping-0.0.1-SNAPSHOT.jar
   
   # 6. 验证
   curl http://localhost:8081/shop-type/list
   ```
   
   ### 6.2 关键配置说明
   
   ```yaml
   # 数据库 — 修改为实际环境
   spring.datasource.url: jdbc:mysql://<host>:3306/hmdp?useSSL=false&serverTimezone=UTC
   spring.datasource.username: root
   spring.datasource.password: root
   
   # Redis — 修改为实际环境
   spring.redis.host: <host>
   spring.redis.port: 6379
   
   # RocketMQ — NameServer 地址
   rocketmq.name-server: <host>:9876
   
   # XXL-JOB — 调度中心地址
   xxl.job.admin.addresses: http://<host>:8080/xxl-job-admin
   xxl.job.executor.port: 9999
   
   # Sentinel — 控制台地址（可选）
   spring.cloud.sentinel.transport.dashboard: <host>:8080
   ```
   
   ### 6.3 中间件启动顺序
   
   ```
   MySQL → Redis → RocketMQ NameServer → RocketMQ Broker → XXL-JOB Admin → (Sentinel Dashboard) → 本项目
   ```
   
   ---
   
   ## 七、接口说明
   
   ### 7.1 通用规范
   
   - **请求格式**：`application/json`（GET 请求使用 Query String）
   - **返回格式**：统一封装为 `Result` 对象
   
   ```json
   {
     "success": true,
     "errorMsg": null,
     "data": {},
     "total": 100
   }
   ```
   
   - **错误返回**：`success=false`，`errorMsg` 包含错误信息
   
   ### 7.2 核心接口一览
   
   #### 用户模块 `/user`
   
   | 方法 | 路径 | 说明 |
   |---|---|---|
   | POST | `/user/code?phone=xxx` | 发送短信验证码 |
   | POST | `/user/login` | 手机号 + 验证码登录 |
   | POST | `/user/logout` | 退出登录 |
   | GET | `/user/me` | 获取当前用户信息 |
   | GET | `/user/{id}` | 查询用户详情 |
   | POST | `/user/sign` | 每日签到 |
   | GET | `/user/sign/count` | 本月签到天数 |
   
   #### 商户模块 `/shop`
   
   | 方法 | 路径 | 说明 |
   |---|---|---|
   | GET | `/shop/{id}` | 查询商户详情（含缓存） |
   | POST | `/shop` | 新增商户 |
   | PUT | `/shop` | 更新商户 |
   | GET | `/shop/of/type?typeId=&current=&x=&y=` | 按类型 + 坐标查询附近商户 |
   | GET | `/shop/of/name?name=&current=` | 按名称搜索商户 |
   
   #### 探店笔记 `/blog`
   
   | 方法 | 路径 | 说明 |
   |---|---|---|
   | POST | `/blog` | 发布笔记 |
   | GET | `/blog/{id}` | 查看笔记详情 |
   | GET | `/blog/hot?current=` | 热门笔记列表 |
   | PUT | `/blog/like/{id}` | 点赞/取消点赞 |
   | GET | `/blog/likes/{id}` | 笔记点赞用户 Top5 |
   | GET | `/blog/of/follow?lastId=&offset=` | 关注好友 Feed 流 |
   
   #### 秒杀模块 `/voucher-order`
   
   | 方法 | 路径 | 说明 |
   |---|---|---|
   | POST | `/voucher-order/seckill/{id}` | 秒杀抢券（含 Sentinel 限流） |
   
   #### 支付模块 `/api/pay`
   
   | 方法 | 路径 | 说明 |
   |---|---|---|
   | POST | `/api/pay/callback?orderId=xxx` | 模拟支付回调 |
   
   #### 关注模块 `/follow`
   
   | 方法 | 路径 | 说明 |
   |---|---|---|
   | PUT | `/follow/{id}/{isFollow}` | 关注/取关用户 |
   | GET | `/follow/or/not/{id}` | 是否已关注 |
   | GET | `/follow/common/{id}` | 共同关注列表 |
   
   ---
   
   ## 八、注意事项 & 后续优化方向
   
   ### 8.1 注意事项
   
   1. **中间件依赖**：项目强依赖 Redis、MySQL、RocketMQ，启动前需确保它们已就绪
   2. **XXL-JOB 任务注册**：`likeSyncJob` 定时任务需在 XXL-JOB Admin 控制台手动配置 Cron 表达式（建议 `0 */5 * * * ?`），否则点赞数据不会同步到 MySQL
   3. **Sentinel 规则持久化**：默认 Sentinel 规则存储在内存中，服务重启后丢失，生产环境需配置持久化（如 Nacos/Redis）
   4. **RocketMQ 延迟级别**：延迟消息使用 `delayLevel=4`，对应默认的 30 秒（非文档所述的 15 分钟），生产环境如需 15 分钟建议使用自定义延迟级别或配置 Broker 的 `messageDelayLevel`
   5. **数据库唯一索引**：`tb_voucher_order` 表需建立 `(user_id, voucher_id)` 唯一索引以支持一人一单的幂等保证
   6. **Redis 数据一致性**：Write-Behind 模式下的点赞数据在定时任务同步前与 MySQL 存在短暂不一致，仅用于点赞计数场景，不影响业务正确性
   
   ### 8.2 后续优化方向
   
   - **消息可靠性**：RocketMQ 当前使用同步发送，可引入事务消息或本地消息表保证 Lua 执行和 MQ 发送的原子性
   - **库存预热**：秒杀库存可提前从 MySQL 加载到 Redis，避免冷启动时 Redis 无库存数据
   - **读写分离**：MySQL 可引入主从复制 + ShardingSphere，分担读压力
   - **接口鉴权**：当前仅基于拦截器校验登录态，可引入 Spring Security + JWT 实现更完善的权限控制
   - **全链路压测**：秒杀场景建议使用 JMeter 压测验证 Sentinel 限流阈值和系统容量
   - **容器化部署**：编写 Dockerfile + docker-compose.yml，实现一键部署
   
   ---
   
   ## 数据库表结构
   
   | 表名 | 说明 |
   |---|---|
   | `tb_user` | 用户表（手机号 + 密码） |
   | `tb_user_info` | 用户扩展信息（城市、粉丝、关注、会员等级） |
   | `tb_shop` | 商户表（名称、坐标、评分） |
   | `tb_shop_type` | 商户类型（美食、KTV 等） |
   | `tb_blog` | 探店笔记 |
   | `tb_blog_like_count` | 笔记点赞计数表 |
   | `tb_blog_comments` | 笔记评论（支持楼中楼回复） |
   | `tb_voucher` | 优惠券（普通券 + 秒杀券） |
   | `tb_seckill_voucher` | 秒杀优惠券（库存、起止时间） |
   | `tb_voucher_order` | 优惠券订单（含支付、退款、核销状态） |
   | `tb_follow` | 用户关注关系 |
   | `tb_sign` | 用户签到记录 |
   
   完整建表 SQL 见 `src/main/resources/db/hmdp.sql`。
   
   ---
   
   ## Redis Key 设计
   
   | Key 模式 | 类型 | 说明 |
   |---|---|---|
   | `login:token:{uuid}` | String | 用户登录 Token |
   | `login:code:{phone}` | String | 短信验证码（TTL 2min） |
   | `cache:shop:{id}` | String | 商户缓存（TTL 30min） |
   | `lock:shop:{id}` | String | 商户缓存重建锁（TTL 10s） |
   | `seckill:stock:{voucherId}` | String | 秒杀库存计数 |
   | `seckill:order:{voucherId}` | Set | 已下单用户集合 |
   | `blog:liked:{blogId}` | ZSet | 笔记点赞用户 + 时间戳 |
   | `blog:like:count:{blogId}` | String | 笔记点赞总数（Write-Behind） |
   | `blog:like:changed` | Set | 点赞变更的博客 ID 脏数据集合 |
   | `feed:{userId}` | ZSet | 用户 Feed 收件箱 |
   | `shop:geo:{typeId}` | GEO | 商户地理位置 |
   | `sign:{userId}:{yyyyMM}` | BitMap | 用户月度签到记录 |
   | `follows:{userId}` | Set | 用户关注集合 |
   
   ---
   
   ## License
   
   MIT
   