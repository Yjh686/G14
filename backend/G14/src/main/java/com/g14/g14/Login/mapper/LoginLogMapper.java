package com.g14.g14.login.mapper;

import com.g14.g14.login.entity.LoginLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 登录日志 Mapper。SQL 定义见 resources/mappers/LoginLogMapper.xml。
 */
@Mapper
public interface LoginLogMapper {

    int insert(LoginLog log);
}
