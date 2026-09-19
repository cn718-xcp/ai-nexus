package com.ainexus.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 注册请求。
 */
@Data
public class RegisterRequest {

    @NotBlank(message = "用户名不能为空")
    @Size(min = 4, max = 32, message = "用户名长度 4-32")
    @Pattern(regexp = "^[a-zA-Z0-9_]+$", message = "用户名仅允许字母/数字/下划线")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Size(min = 8, max = 64, message = "密码长度 8-64")
    private String password;
}
