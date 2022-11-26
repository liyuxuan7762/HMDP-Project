package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.dto.Result;
import com.dto.ScrollResult;
import com.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static com.hmdp.utils.RedisConstants.BLOG_LIKE_KEY;
import static com.hmdp.utils.RedisConstants.FEED_MESSAGEBOX_KEY;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    IFollowService followService;

    @Override
    public Result queryBlogById(Integer id) {
        Blog blog = super.getById(id);
        if (blog == null) {
            return Result.fail("笔记不存在");
        }
        this.queryBlogUser(blog);
        this.isBlogLiked(blog);
        return Result.ok(blog);
    }

    /**
     * 查找热门笔记
     *
     * @param current
     * @return
     */
    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = super.query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach((blog) -> {
            this.queryBlogUser(blog);
            this.isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    /**
     * 实现一个用户只能点一次赞
     *
     * @param id
     * @return
     */
    @Override
    public Result likeBlog(Long id) {
        // TODO 首先到redis中查询该用户是否点过赞
        // 1.获取当前用户 如果用户没有登录 提示用户未登录不能点赞
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("用户未登录不能点赞");
        }
        // 2.查询用户是否已经点过赞了
        Double score = stringRedisTemplate.opsForZSet().score(BLOG_LIKE_KEY + id, user.getId().toString());
        // TODO 如果没有点过赞 则点赞
        if (score == null) {
            // TODO 将用户id写入redis，like+1
            boolean isSuccess = super.update().setSql("liked = liked + 1").eq("id", id).update();
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().add(BLOG_LIKE_KEY + id, user.getId().toString(), System.currentTimeMillis());
            }
        } else {
            // TODO 如果用户已经点过赞
            // TODO 从redis中移除用户id, like-1
            boolean isSuccess = super.update().setSql("liked = liked - 1").eq("id", id).update();
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().remove(BLOG_LIKE_KEY + id, user.getId().toString());
            }
        }

        return Result.ok();
    }

    /**
     * 根据笔记ID查询前五名点赞的用户信息
     *
     * @param id
     * @return
     */
    @Override
    public Result queryBlogLikes(Integer id) {
        // TODO 根据笔记ID到Redis中查询前五名的用户id
        Set<String> userSet = stringRedisTemplate.opsForZSet().range(BLOG_LIKE_KEY + id, 0, 4);
        if (userSet == null || userSet.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        // TODO 根据用户id查询用户
        List<UserDTO> top5 = new ArrayList<>();
        Long userId = null;
        User user = null;
        UserDTO dto = null;
        for (String s : userSet) {
            userId = Long.valueOf(s);
            user = userService.getById(userId);
            dto = new UserDTO();
            BeanUtil.copyProperties(user, dto);
            top5.add(dto);
        }
        // TODO 封装用户DTO返回
        return Result.ok(top5);
    }

    /**
     * 保存笔记到数据库 同时将笔记发送到所有粉丝的收件箱中
     * @param blog
     * @return
     */
    @Override
    @Transactional
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 保存探店博文
        boolean isSuccess = this.save(blog);
        if (isSuccess) {
            // 如果成功 则将博文ID推送到当前用户粉丝的收件箱中
            // 获取当前用户的所有粉丝
            List<Follow> follows = followService.query().eq("user_id", user.getId()).list();
            long time = System.currentTimeMillis();
            // 将消息推送到粉丝收件箱
            for (Follow follow : follows) {
                stringRedisTemplate.opsForZSet().add(FEED_MESSAGEBOX_KEY + follow.getFollowUserId(), blog.getId().toString(), time);
            }

        }
        // 返回id
        return Result.ok(blog.getId());
    }

    /**
     * 滚动分页查询关注的人发送的博文
     * @param max 滚动分页查询起始的时间戳
     * @param offset 已经查询的结果当中时间戳最小值相同的个数
     * @return
     */
    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        // TODO 获取当前用户 只有登录才能进入到个人主页查看关注博文 所以这里一定登陆了
        Long userId = UserHolder.getUser().getId();
        String key = FEED_MESSAGEBOX_KEY + userId;
        // TODO 获取当前用户的信箱
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet().reverseRangeByScoreWithScores(key, 0, max, offset, 3);
        // TODO 判断是否信箱为空
        if (typedTuples == null || typedTuples.isEmpty()) {
            return Result.ok();
        }
        // TODO 如果不为空 则解析信箱数据 并计算出下一次的max和offset
        List<Long> blogIdList = new ArrayList<>(typedTuples.size());
        Long minTimeStamp = 0L;
        int os = 1; // 记录offset
        for (ZSetOperations.TypedTuple<String> typedTuple : typedTuples) {
            blogIdList.add(Long.valueOf(typedTuple.getValue()));
            // 循环结束记录的正好是最后一条博文的时间戳，最后一条博文的时间戳就是最小的时间戳
            // 5 5 4 3
            long timeStamp = typedTuple.getScore().longValue();
            if (minTimeStamp == timeStamp) {
                os++;
            } else {
                minTimeStamp = timeStamp;
                os = 1;
            }
        }
        // TODO 根据博文ID查询博文 和相关的用户信息和点赞信息
        String idsStr = StrUtil.join(",", blogIdList);
        List<Blog> blogList = super.query().in("id", blogIdList).last("order by field(id, " + idsStr + ")").list();
        // 查询作者和点赞信息
        for (Blog blog : blogList) {
            this.queryBlogUser(blog);
            this.isBlogLiked(blog);
        }

        // TODO 封装返回对象
        ScrollResult r = new ScrollResult();
        r.setList(blogList);
        r.setOffset(os);
        r.setMinTime(minTimeStamp);

        return Result.ok(r);
    }


    /**
     * 查找笔记作者
     *
     * @param blog
     */
    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }

    /**
     * 判断当前用户是否点赞
     *
     * @param blog
     */
    private void isBlogLiked(Blog blog) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return;
        }
        // 2.查询用户是否已经点过赞了
        Double score = stringRedisTemplate.opsForZSet().score(BLOG_LIKE_KEY + blog.getId(), user.getId().toString());
        blog.setLike(score != null);
    }
}
