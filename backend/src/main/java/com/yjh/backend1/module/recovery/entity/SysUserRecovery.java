package com.yjh.backend1.module.recovery.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 密保问题，对应表 {@code sys_user_recovery}。
 * <p>
 * 不继承 {@link com.yjh.backend1.common.BaseEntity}：这张表没有 deleted 列。
 * 「一人一行」由 {@code uk_user} 唯一索引保证，修改密保走 update 同一行，
 * 所以没有需要追溯的删除历史 —— 取消密保等于删除整行，那本身就是一次可记录的操作。
 * <p>
 * 答案只存 BCrypt 哈希。这个决定不是为了「看起来安全」：很多人的密保答案是
 * 学号、生日、宿舍号，这些信息在班级群里公开，明文存等于给每个账号配了第二把
 * 明码钥匙。
 */
@Data
@TableName("sys_user_recovery")
public class SysUserRecovery implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 一人一行 */
    private Long userId;

    private String question1;
    /** 答案 1 的 BCrypt 哈希 */
    private String answerHash1;

    private String question2;
    /** 答案 2 的 BCrypt 哈希 */
    private String answerHash2;

    /** 连续验证失败次数 */
    private Integer failCount;
    /** 验证锁定截止时间，null 表示未锁定 */
    private LocalDateTime lockedUntil;
    /** 最近一次验证成功时间 */
    private LocalDateTime lastVerifyTime;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /**
     * 是否处于验证锁定中。
     * <p>
     * 方法名刻意不叫 {@code isLocked()}：Lombok 会为 {@code lockedUntil} 生成
     * {@code getLockedUntil()}，再加一个 {@code isLocked()} 不会冲突，但与
     * {@code Survey.isLimitOnce()} 那类「非法重载 getter」踩过的坑同源，
     * 统一用动词短语命名，避免 MyBatis OGNL 在反射时把属性名推错。
     */
    public boolean lockedNow() {
        return lockedUntil != null && lockedUntil.isAfter(LocalDateTime.now());
    }
}
