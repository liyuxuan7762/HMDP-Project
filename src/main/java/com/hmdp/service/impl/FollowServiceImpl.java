package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static com.hmdp.utils.RedisConstants.FOLLOW_LIST_KEY;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserService userService;

    /**
     * 判断当前用户是否关注该用户id
     *
     * @param id 用户id
     * @return
     */
    @Override
    public Result isFollowed(Long id) {
        // 1. 获取当前用户
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.ok(false);
        }
        // 2.判断是否关注
        Integer count = super.query().eq("user_id", user.getId()).eq("follow_user_id", id).count();
        return Result.ok(count > 0);
    }

    @Override
    public Result follow(Long id, Boolean isFollowed) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("用户未登录");
        }
        if (isFollowed) {
            // 关注
            Follow follow = new Follow();
            follow.setUserId(user.getId());
            follow.setFollowUserId(id);
            boolean isSuccess = super.save(follow);
            if (isSuccess) {
                stringRedisTemplate.opsForSet().add(FOLLOW_LIST_KEY + user.getId(), id.toString());
            }
        } else {
            // 取关
            QueryWrapper<Follow> queryWrapper = new QueryWrapper<>();
            queryWrapper.eq("user_id", user.getId());
            queryWrapper.eq("follow_user_id", id);
            boolean isSuccess = super.remove(queryWrapper);
            if (isSuccess) {
                stringRedisTemplate.opsForSet().remove(FOLLOW_LIST_KEY + user.getId(), id.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result common(Long id) {
        // 获取当前用户
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.ok(Collections.emptyList());
        }
        // 根据用户id计算
        Set<String> commonSet = stringRedisTemplate.opsForSet().intersect(FOLLOW_LIST_KEY + user.getId(), FOLLOW_LIST_KEY + id);
        if (commonSet.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        List<UserDTO> commonFollow = new ArrayList<>();
        Long userId = null;
        User u = null;
        UserDTO dto = null;
        for (String s : commonSet) {
            userId = Long.valueOf(s);
            u = userService.getById(userId);
            dto = new UserDTO();
            BeanUtil.copyProperties(u, dto);
            commonFollow.add(dto);
        }
        return Result.ok(commonFollow);
    }
}
