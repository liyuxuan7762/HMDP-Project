package com.hmdp.service.impl;

import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;

import static com.hmdp.utils.SystemConstants.*;


/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 1.验证手机号是否合法
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 2.如果不符合，则返回错误信息
            return Result.fail("手机号码格式不正确");
        }
        // 3.符合则生成验证码并放入session
        String code = RandomUtil.randomNumbers(6);
        session.setAttribute(USER_CODE, code);
        // 4.发送验证码
        log.info("验证码发送成功，验证码为" + code);
        // 5.返回结果信息
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 1.验证手机号是否合法
        if (RegexUtils.isPhoneInvalid(loginForm.getPhone())) {
            // 2.如果不符合，则返回错误信息
            return Result.fail("手机号码格式不正确");
        }
        // 2.判断验证码是否相同
        Object sessionCode = session.getAttribute(USER_CODE);
        if (loginForm.getCode() == null || !sessionCode.toString().equals(loginForm.getCode())) {
            return Result.fail("验证码错误");
        }
        // 3.根据手机号查询用户
        User user = super.getOne(new LambdaQueryWrapper<User>().eq(User::getPhone, loginForm.getPhone()));
        if (user == null) {
            // 4.如果用户不存在则创建用户
            user = createUserWithPhone(loginForm.getPhone());
        }
        // 5.将用户保存到session
        session.setAttribute(USER_SESSION, user);
        // 6.返回相应信息
        return Result.ok();
    }

    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        super.save(user);
        return user;
    }
}
