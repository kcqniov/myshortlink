package com.szs.shortlink.admin.controller;

import com.szs.shortlink.admin.common.convention.result.Result;
import com.szs.shortlink.admin.common.convention.result.Results;
import com.szs.shortlink.admin.dto.req.UserLoginReqDTO;
import com.szs.shortlink.admin.dto.req.UserRegisterReqDTO;
import com.szs.shortlink.admin.dto.req.UserUpdateReqDTO;
import com.szs.shortlink.admin.dto.resp.UserActualRespDTO;
import com.szs.shortlink.admin.dto.resp.UserLoginRespDTO;
import com.szs.shortlink.admin.dto.resp.UserRespDTO;
import com.szs.shortlink.admin.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    /**
     *
     */
    @GetMapping("/api/short-link/admin/v1/user/{username}")
    public Result<UserRespDTO> getUserByUsername(@PathVariable("username") String username){
         UserRespDTO result = userService.getUserByUsername(username);
         return Results.success(result);
    }

    @GetMapping("/api/short-link/admin/v1/actual/user/{username}")
    public Result<UserActualRespDTO> getActualUserByUsername(@PathVariable("username") String username){
        UserActualRespDTO result = userService.getActualUserByUsername(username);
        return Results.success(result);
    }

    @GetMapping("/api/short-link/admin/v1/user/has-username/{username}")
    public Result<Boolean> hasUsername(@PathVariable("username") String username){
        boolean result = userService.hasUsername(username);
        return Results.success(result);
    }

    /**
     * 注册用户
     */
    @PostMapping("/api/short-link/admin/v1/user")
    public Result<Void> Register(@RequestBody UserRegisterReqDTO requestParam) {
        userService.Register(requestParam);
        return Results.success();
    }

    @PutMapping("/api/short-link/admin/v1/user")
    public Result<Void> update(@RequestBody UserUpdateReqDTO requestParam) {
        userService.update(requestParam);
        return Results.success();
    }

    /**
     * 用户登录
     */
    @PostMapping("/api/short-link/admin/v1/user/login")
    public Result<UserLoginRespDTO> login(@RequestBody UserLoginReqDTO requestParam) {
        return Results.success(userService.login(requestParam));
    }

    /**
     * 检查用户是否登录
     */
    @GetMapping("/api/short-link/admin/v1/user/check-login")
    public Result<Boolean> checkLogin(@RequestParam("username") String username, @RequestParam("token") String token) {
        return Results.success(userService.checkLogin(username, token));
    }

    /**
     * 用户退出登录
     */
    @DeleteMapping("/api/short-link/admin/v1/user/logout")
    public Result<Void> logout(@RequestParam("username") String username, @RequestParam("token") String token) {
        userService.logout(username, token);
        return Results.success();
    }
}
