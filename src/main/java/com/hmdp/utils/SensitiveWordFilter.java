package com.hmdp.utils;

import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Component
public class SensitiveWordFilter {

    private final TrieNode root = new TrieNode();

    @PostConstruct
    public void init() {
        Set<String> words = new HashSet<>();
        words.add("傻瓜");
        words.add("笨蛋");
        words.add("垃圾");
        for (String word : words) {
            insert(word);
        }
    }

    public String filter(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        char[] chars = text.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            TrieNode node = root;
            int end = -1;
            for (int j = i; j < chars.length; j++) {
                node = node.children.get(chars[j]);
                if (node == null) {
                    break;
                }
                if (node.isWord) {
                    end = j;
                }
            }
            if (end >= i) {
                for (int k = i; k <= end; k++) {
                    chars[k] = '*';
                }
                i = end;
            }
        }
        return new String(chars);
    }

    private void insert(String word) {
        TrieNode current = root;
        for (char c : word.toCharArray()) {
            current = current.children.computeIfAbsent(c, key -> new TrieNode());
        }
        current.isWord = true;
    }

    private static class TrieNode {
        private final Map<Character, TrieNode> children = new HashMap<>();
        private boolean isWord;
    }
}
