package com.yjh.backend1.module.survey.dto;

import lombok.Data;

/**
 * 选项展示对象。
 */
@Data
public class OptionVO {

    /** 选项 ID；答卷里记录的选项值就是它 */
    private Long id;
    private String content;
    private Integer sortNo;
}
