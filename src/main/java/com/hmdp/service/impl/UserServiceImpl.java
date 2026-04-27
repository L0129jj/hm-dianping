package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    @Override
    public Result logout(String token) {
        if (token == null || token.isBlank()) {
            return Result.ok();
        }
        stringRedisTemplate.delete(LOGIN_USER_KEY + token);
        return Result.ok();
    }

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    /**
     发送短信验证码
     *
     */
    @Override
    public Result sendCode(String phone, HttpSession session) {
//        1.校验手机号
        if(RegexUtils.isPhoneInvalid(phone)) {
//        2.不符合，返回错误信息
            return Result.fail("手机号格式错误！");
        }
        //3.符合，生成验证码
        String code= RandomUtil.randomNumbers(6);
//        4.保存验证码到redis
        stringRedisTemplate.opsForValue().set(RedisConstants.LOGIN_CODE_KEY+phone,code,RedisConstants.LOGIN_CODE_TTL, TimeUnit.MINUTES);
//        5.发送验证码
        log.debug("发送验证码：{}",code);

        return Result.ok();
    }

    /**
     *  登录、注册
     */
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        String phone=loginForm.getPhone();
        //1.校验手机号
        if(RegexUtils.isPhoneInvalid(phone)) {
//        不符合，返回错误信息
            return Result.fail("手机号格式错误！");
        }
        //2.从redis获取验证码并检验
        String cacheCode=stringRedisTemplate.opsForValue().get("code");
        String code=loginForm.getCode();
        if (cacheCode != null && (!cacheCode.equals(code) || RegexUtils.isCodeInvalid(code))) {
            //3.不一致，返回错误信息
            return Result.fail("验证码错误！");
        }
        //4.一致，根据手机号查询用户
        User user=query().eq("phone",phone).one();
        //5.判断用户是否存在
        if(user==null) {
            //6.不存在，创建新用户
            user=createUser(phone);
        }
        //7.保存用户到redis
        //7.1随机生成token，作为登录令牌
        String token= UUID.randomUUID().toString();
        //7.2 将user对象转为map
        UserDTO userDTO=BeanUtil.copyProperties(user,UserDTO.class);
        Map<String, Object> userMap=BeanUtil.beanToMap(userDTO,new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName,fieldValue)->fieldValue.toString()));
        //7.3存储
        String tokenKey=LOGIN_USER_KEY+token;
        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
        //7.4刷新token有效期
        stringRedisTemplate.expire(tokenKey,LOGIN_USER_TTL,TimeUnit.MINUTES);
        //8.返回token
        return Result.ok(token);
    }

    @Override
    public Result queryUserById(Long userId) {
        // 查询详情
        User user = getById(userId);
        if (user == null) {
            return Result.ok();
        }
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        // 返回
        return Result.ok(userDTO);
    }

    @Override
    public Result sign() {
        //1.获取当前用户id
        UserDTO user = UserHolder.getUser();
        //2.获取当前日期
        LocalDateTime now = LocalDateTime.now();
        //3.拼接key
        String suffixKey = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key=USER_SIGN_KEY+user.getId()+suffixKey;
        //4.获取当前是当月的第几天
        int dayOfMonth = now.getDayOfMonth();
        //5.写入redis，BitMap
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth-1, true);
        return Result.ok();
    }

    @Override
    public Result signCount() {
        //1.获取当前用户id
        UserDTO user = UserHolder.getUser();
        //2.获取当前日期
        LocalDateTime now = LocalDateTime.now();
        //3.拼接key
        String suffixKey = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key=USER_SIGN_KEY+user.getNickName()+suffixKey;
        //4.获取当前是当月的第几天
        int dayOfMonth = now.getDayOfMonth();
        //5.获取本月目前为止的所有签到记录  ，返回的是一个十进制数字， BITFIELD
        List<Long> result = stringRedisTemplate.opsForValue().bitField(key
                , BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0));
        if (result==null) {
            //5.1.无签到记录
            return Result.ok(0);
        }
        Long num = result.getFirst();
        if (num==0) {
            return Result.ok(0);
        }
        int count=0;
        //6.循环遍历
        while ((num & 1) == 1){
            //为1则签到天数+1
            count++;
            num >>>=1;
        }
        return Result.ok(count);
    }

    private User createUser(String phone) {
        User user=new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX+RandomUtil.randomString(10));
        user.setCreateTime(LocalDateTime.now());
        user.setUpdateTime(LocalDateTime.now());
        save(user);
        return user;
    }
}
