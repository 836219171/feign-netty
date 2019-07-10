package com.intellif.provider.web;

import com.intellif.provider.bean.User;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @author inori
 * @create 2019-07-02 13:44
 */
@RestController
public class NameProvider {

    @GetMapping("/user")
    public User getUser() {
        return new User("sweet", "女", 18);
    }

}