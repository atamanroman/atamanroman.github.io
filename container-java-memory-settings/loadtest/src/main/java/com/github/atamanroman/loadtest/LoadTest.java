package com.github.atamanroman.loadtest;

import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

@SpringBootApplication
@RestController
public class LoadTest {

    Random rand = new Random();
    HashFunction hash = Hashing.goodFastHash(128);

    public static void main(String[] args) {
        SpringApplication.run(LoadTest.class, args);
    }

    @GetMapping
    public Map<String, Object> doWork() {
        byte[] bytes = new byte[1024 * 1024 * 10];// 10m;
        rand.nextBytes(bytes);

        HashMap<String, Object> result = new HashMap<>();
        result.put("hash", hash.hashBytes(bytes).toString());
        return result;
    }
}
