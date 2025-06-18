package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.util.Map;
import java.util.concurrent.TimeUnit;

//这个类不是由spring创建的，是手动创建的，因此不能用autowired, 这个类在webmvcconfig中使用, mvcconfig是spring自动管理的，可以从那里获得redistemplate
//拦截器生效需要在mvc config中配置，实现webmvcconfigurer接口，重写addInteceptors方法
public class LoginInterceptor implements HandlerInterceptor {

//    private StringRedisTemplate stringRedisTemplate;
//
//    public LoginInterceptor(StringRedisTemplate stringRedisTemplate) {
//        this.stringRedisTemplate = stringRedisTemplate;
//    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
//        //获取token
//        String token = request.getHeader("authorization");
//        if(StrUtil.isBlank(token)){
//            //不存在，拦截
//            response.setStatus(401);
//            return false;
//        }
//
//        //基于token获取redis中的用户
//        String key = RedisConstants.LOGIN_USER_KEY + token;
//        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(key);
//
//        //判断用户是否存在
//        if(userMap.isEmpty()){
//            //不存在，拦截
//            response.setStatus(401);
//            return false;
//        }
//
//        //将查询到的数据转换成userdto对象
//        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);//都是使用工具类方便而已
//
//        //存在，保存用户信息到threadlocal
//        UserHolder.saveUser(userDTO);
//
//        //刷新token有效期
//        stringRedisTemplate.expire(key, RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);
//
//        //放行
//        return true;

//----------------------------------------------------------------

        // 1.判断是否需要拦截（ThreadLocal中是否有用户）
        if (UserHolder.getUser() == null) {
            // 没有，需要拦截，设置状态码
            response.setStatus(401);
            // 拦截
            return false;
        }
        // 有用户，则放行
        return true;

    }
    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler, ModelAndView modelAndView) throws Exception {
        //移除用户
        UserHolder.removeUser();
    }
}
