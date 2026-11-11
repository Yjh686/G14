package com.yjh.backend1.module.user.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * ADMIN 修改用户。账号（username）不可改，避免历史数据里的归属对不上。
 */
@Data
public class UpdateUserRequest {

    @Size(max = 64, message = "姓名过长")
    private String realName;

    @Size(max = 128, message = "院系/班级过长")
    private String department;

    @Pattern(regexp = "^$|^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;

    @Size(max = 128, message = "邮箱过长")
    private String email;

    @Size(max = 255, message = "备注过长")
    private String remark;

    @Pattern(regexp = "^(ROLE_ADMIN|ROLE_TEACHER|ROLE_STUDENT)$", message = "角色不合法")
    private String roleCode;
}
