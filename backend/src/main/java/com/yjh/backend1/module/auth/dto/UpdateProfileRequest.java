package com.yjh.backend1.module.auth.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 修改自己的资料。只允许改这几项，账号与角色不可自助变更。
 */
@Data
public class UpdateProfileRequest {

    @Size(max = 64, message = "姓名过长")
    private String realName;

    @Size(max = 20, message = "手机号过长")
    private String phone;

    @Size(max = 128, message = "邮箱过长")
    private String email;

    @Size(max = 128, message = "院系/班级过长")
    private String department;
}
