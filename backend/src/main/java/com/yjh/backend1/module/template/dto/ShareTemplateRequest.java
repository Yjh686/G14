package com.yjh.backend1.module.template.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 把问卷共享为模板。
 * <p>
 * 只填「模板自己」的属性；题目结构由后端从来源问卷现取快照，
 * 前端不能篡改 —— 否则共享出去的模板可能和来源问卷对不上，审核员无从判断。
 */
@Data
public class ShareTemplateRequest {

    /** 来源问卷 */
    @NotNull(message = "请选择要共享的问卷")
    private Long sourceSurveyId;

    @NotBlank(message = "请填写模板名称")
    @Size(max = 120, message = "模板名称不能超过 120 字")
    private String title;

    /** 适用场景说明：审核员和浏览者都靠它判断这个模板该不该通过 */
    @Size(max = 500, message = "适用场景不能超过 500 字")
    private String description;

    /** 分类，空则继承来源问卷的分类 */
    private String category;

    /** 使用模板建卷时的默认值，空则继承来源问卷 */
    private Integer anonymousDefault;
    private Integer limitOnceDefault;
}
