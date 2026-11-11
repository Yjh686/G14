package com.yjh.backend1.module.config.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 系统配置键值对，对应表 {@code sys_config}。
 * <p>
 * 不继承 {@code BaseEntity}：这张表没有 update_by 语义上的"业务操作人"，
 * 而且 {@code config_key} 是业务主键，直接用 @TableId 反而绕开唯一键校验。
 */
@Data
@TableName("sys_config")
public class SysConfig implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 配置键，如 site.name。建表时是唯一键，不可修改。 */
    private String configKey;

    /** 配置值。一律按字符串存，读的时候按 {@link #configType} 解释。 */
    private String configValue;

    /** STRING / TEXT / NUMBER / BOOLEAN / PASSWORD */
    private String configType;

    /** SITE / SECURITY / OTHER，仅用于后台分组展示 */
    private String groupName;

    /** 给管理员看的中文说明 */
    private String description;

    /** 1 = 未登录也能读（站点名、公告、密码规则） */
    private Integer isPublic;

    /** 1 = 系统内置，可改值但不可删除 */
    private Integer builtin;

    @TableField(value = "create_by", fill = FieldFill.INSERT)
    private Long createBy;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(value = "update_by", fill = FieldFill.INSERT_UPDATE)
    private Long updateBy;

    @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    @TableField("deleted")
    private Integer deleted;

    public boolean publicVisible() {
        return Integer.valueOf(1).equals(isPublic);
    }

    /**
     * 是否系统内置项。
     * <p>
     * 方法名刻意不叫 {@code isBuiltin()}：Lombok 会生成 {@code getBuiltin()}，
     * 而字段名 {@code builtin} 与 {@code isBuiltin()} 推导出的属性名相同，
     * 两者构成「非法重载 getter」，MyBatis 构建 OGNL 时会抛
     * {@code ReflectionException: Illegal overloaded getter method}。
     * 问卷模块的 {@code Survey.isLimitOnce()} 已经在这里踩过一次。
     */
    public boolean builtinOnly() {
        return Integer.valueOf(1).equals(builtin);
    }
}
