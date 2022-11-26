package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.dto.LoginFormDTO;
import com.dto.Result;
import com.hmdp.entity.User;

import javax.servlet.http.HttpSession;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IUserService extends IService<User> {



    Result login(LoginFormDTO loginForm);

    Result sendCode(String phone, HttpSession session);
}
