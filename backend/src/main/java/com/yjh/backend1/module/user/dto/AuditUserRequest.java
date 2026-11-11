package com.yjh.backend1.module.user.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * ADMIN 审核注册申请。
 * <p>
 * {@code auditStatus}: 1=通过 2=驳回。驳回必须写原因，否则用户不知道该怎么改。
 */
@Data
public class AuditUserRequest {

    @NotNull(message = "请选择审核结果")
    private Integer auditStatus;

    @Size(max = 255, message = "审核意见过长")
    private String auditRemark;
}
