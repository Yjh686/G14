package com.g14.g14.login.mapper;

import com.g14.g14.login.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户表 Mapper。SQL 定义见 resources/mappers/UserMapper.xml。
 */
@Mapper
public interface UserMapper {

    User selectById(@Param("id") Long id);

    User selectByAccount(@Param("account") String account);

    User selectByPhone(@Param("phone") String phone);

    int countByAccount(@Param("account") String account);

    int countByPhone(@Param("phone") String phone);

    int insert(User user);

    /** 登录成功：清零失败计数与锁定时间，并记录最后登录时间。 */
    int onLoginSuccess(@Param("id") Long id, @Param("loginTime") LocalDateTime loginTime);

    /** 登录失败：累加失败次数，达到阈值时写入锁定截止时间。 */
    int onLoginFail(@Param("id") Long id,
                    @Param("loginFailCount") int loginFailCount,
                    @Param("lockedUntil") LocalDateTime lockedUntil);

    /**
     * 改密 / 重置密码：更新哈希、令牌版本号 +1、重置强制改密标记，并清零失败计数与锁定。
     */
    int updatePassword(@Param("id") Long id,
                       @Param("passwordHash") String passwordHash,
                       @Param("tokenVersion") int tokenVersion,
                       @Param("mustChangePassword") int mustChangePassword);

    /** 变更账号状态，同时推进令牌版本号，使已签发令牌立即失效。 */
    int updateStatus(@Param("id") Long id,
                     @Param("status") int status,
                     @Param("tokenVersion") int tokenVersion);

    List<User> selectPage(@Param("keyword") String keyword,
                          @Param("role") String role,
                          @Param("status") Integer status,
                          @Param("offset") int offset,
                          @Param("limit") int limit);

    long countPage(@Param("keyword") String keyword,
                   @Param("role") String role,
                   @Param("status") Integer status);
}
