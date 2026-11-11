package com.yjh.backend1.module.template.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 模板审核。
 * <p>
 * {@code auditStatus} 只接受 APPROVED / REJECTED 两个值：
 * 把 PENDING 也放进来等于允许「撤销审核」，会让已经通过、已被别人使用的模板凭空回到待审状态。
 * 撤销审核的正确姿势是下架（OFFLINE）。
 */
@Data
public class AuditTemplateRequest {

    /** APPROVED / REJECTED */
    @NotNull(message = "请选择审核结果")
    private String auditStatus;

    /** 驳回原因，驳回时必填 */
    @Size(max = 255, message = "审核意见不能超过 255 字")
    private String auditRemark;
}
