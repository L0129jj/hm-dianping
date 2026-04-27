package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    @Resource
    private IUserService userService;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IFollowService followService;

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            queryUserByBlog(blog);
            isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    @Override
    public Result queryBlogById(Long id) {
        //1.查询blog
        Blog blog = getById(id);
        if (blog==null) {
            return Result.fail("该笔记不存在！");
        }
        //2.查询blog有关的用户
        queryUserByBlog(blog);
        //3.查询用户是否已经点赞
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    private void isBlogLiked(Blog blog) {
        //1.获取当前用户
        UserDTO user = UserHolder.getUser();
        if (user==null) {
            return;
        }
        Long userId = user.getId();
        //2.判断当前用户是否已经点赞
        String key=BLOG_LIKED_KEY+blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score!=null);
    }

    /**
     * 点赞笔记功能
     * @param id
     * @return
     */
    @Override
    public Result likeBlog(Long id) {
        //1.获取当前用户
        UserDTO user = UserHolder.getUser();
        if (user==null) {
            return Result.fail("请先登录！");
        }
        Long userId = user.getId();
        //2.判断当前用户是否已经点赞
        String key=BLOG_LIKED_KEY+id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        //3.未点赞
        if (score==null) {
            //3.1点赞，操作数据库 liked+1
            boolean isSuccess = update().setSql("liked=liked+1").eq("id", id).update();
            if (isSuccess) {
                //3.2 保存用户到redis的zset集合,时间戳作为分数
                stringRedisTemplate.opsForZSet().add(key, userId.toString(),System.currentTimeMillis());
            }
        }
        else {
            //4.已点赞
            //4.1点赞数量-1
            boolean isSuccess = update().setSql("liked=liked-1").eq("id",id).update();
            if (isSuccess) {
                //4.2 将用户从redis的set集合移除
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result queryBlogLikes(Long id) {
        //1.查询点赞排行top5的用户 zrange key 0 4
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(BLOG_LIKED_KEY + id, 0, 4);
        if (top5 == null || top5.isEmpty()) {
            return Result.ok(java.util.Collections.emptyList());
        }
        //2.解析出其中的用户id
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        String idStr = ids.stream().map(String::valueOf).collect(Collectors.joining(","));
        //3.根据用户id查询用户
        List<UserDTO> userDTOS = userService.query()
                .in("id",ids)
                .last("ORDER BY FIELD (id,"+idStr+ ")")
                .list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        //4.返回
        return Result.ok(userDTOS);
    }

    @Override
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 保存探店博文
        boolean isSuccess = save(blog);
        if (!isSuccess) {
           return Result.fail("新增笔记失败！");
        }
        //获取全部粉丝
        List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();
        //向所有粉丝推送笔记
        for (Follow follow : follows) {
            //获取粉丝id
            Long followId = follow.getUserId();
            //推送
            String key=FEED_KEY+followId;
            stringRedisTemplate.opsForZSet().add(key,blog.getId().toString(),System.currentTimeMillis());
        }
        // 返回id
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        //1.获取当前用户
        Long userId = UserHolder.getUser().getId();
        //2.查询收件箱 XREVRANGEBYSCORE key Max Min LIMIT offset count
        String key=FEED_KEY+userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet().reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        //3.非空判断
        if (typedTuples==null||typedTuples.isEmpty()) {
            return Result.ok();
        }
        //4.解析数据 blogId minTime(时间戳)  offset
        long minTime=0;
        int os=1;
        List<Long> ids = new ArrayList<>(typedTuples.size());
        for (ZSetOperations.TypedTuple<String> typedTuple : typedTuples) {
            //4.1 解析id
            ids.add(Long.valueOf(Objects.requireNonNull(typedTuple.getValue())));
            //4.2 获取时间戳
            long time = Objects.requireNonNull(typedTuple.getScore()).longValue();
            if (minTime==time) {
                os++;
            }else {
                minTime = time;
                os=1;
            }
        }
        os=minTime==max?os:os+offset;
        //5.根据id查询blog
        String idStr = StrUtil.join(",", ids);
        List<Blog> blogs = query().in("id", ids).last("ORDER BY FIELD (id," + idStr + ")").list();
        for (Blog blog : blogs) {
            //5.1 查询与笔记有关的用户
            queryUserByBlog(blog);
            //5.2. 查询是否已点赞
            isBlogLiked(blog);
        }

        //6. 封装
        ScrollResult scrollResult = new ScrollResult();
        scrollResult.setList(blogs);
        scrollResult.setOffset(offset);
        scrollResult.setMinTime(minTime);

        return Result.ok(scrollResult);
    }


    private void queryUserByBlog(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
}
