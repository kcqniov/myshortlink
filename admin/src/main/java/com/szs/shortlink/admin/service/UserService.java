package com.szs.shortlink.admin.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.szs.shortlink.admin.dto.req.UserLoginReqDTO;
import com.szs.shortlink.admin.dto.req.UserRegisterReqDTO;
import com.szs.shortlink.admin.dto.req.UserUpdateReqDTO;
import com.szs.shortlink.admin.dto.resp.UserActualRespDTO;
import com.szs.shortlink.admin.dto.resp.UserLoginRespDTO;
import com.szs.shortlink.admin.dto.resp.UserRespDTO;
import com.szs.shortlink.admin.dao.entity.UserDo;

/**
 * 用户接口层
 */
public interface UserService extends IService<UserDo> {
    /**
     * 根据用户名返回用户信息
     * @param username
     * @return
     */
    UserRespDTO getUserByUsername(String username);

    UserActualRespDTO getActualUserByUsername(String username);

    Boolean hasUsername(String username);

    void Register(UserRegisterReqDTO requestParam);

    void update(UserUpdateReqDTO requestParam);

    /**
     * 用户登录
     *
     * @param requestParam 用户登录请求参数
     * @return 用户登录返回参数 Token
     */
    UserLoginRespDTO login(UserLoginReqDTO requestParam);

    /**
     * 检查用户是否登录
     *
     * @param username 用户名
     * @param token    用户登录 Token
     * @return 用户是否登录标识
     */
    Boolean checkLogin(String username, String token);

    /**
     * 退出登录
     *
     * @param username 用户名
     * @param token    用户登录 Token
     */
    void logout(String username, String token);
}
