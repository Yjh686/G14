package com.yjh.backend1.module.answer.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yjh.backend1.module.answer.entity.SurveyDraft;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SurveyDraftMapper extends BaseMapper<SurveyDraft> {

    /**
     * 草稿保存的原子 upsert。
     * <p>
     * 为什么不用「先 selectOne 判断存在与否，再 insert 或 updateById」：
     * 两个并发请求（前端 5s 防抖 + 用户手动切题，或者双开标签页）会同时查到「没有草稿」，
     * 于是都走 insert，第二个撞 {@code uk_survey_user} 抛 DuplicateKeyException → 500。
     * 有人会想「catch 住再查一次改成 update」，但在 MySQL 默认的 REPEATABLE READ 下，
     * 本事务的一致性快照建立于对方提交之前，重新 select 依然看不到对方插入的行，
     * 于是二次 insert 再次冲突 —— 读后写这条路在 RR 隔离级别下修不干净。
     * <p>
     * {@code INSERT ... ON DUPLICATE KEY UPDATE} 由 InnoDB 在同一个语句里完成
     * 「不存在则插入／已存在则加锁更新」，不存在读与写之间的窗口，也就没有竞态。
     * <p>
     * 注意：本语句仍可能与「提交流程里的 DELETE survey_draft」互相死锁，
     * 重试策略在 {@code AnswerService.saveDraft} 里，不在这里（接口里放不了日志与退避逻辑）。
     *
     * @return 影响行数：1 表示新插入，2 表示更新了已有行（MySQL 对 ODKU 的约定）
     */
    @Insert("""
            INSERT INTO survey_draft (survey_id, user_id, answers_json, create_time, update_time)
            VALUES (#{surveyId}, #{userId}, #{answersJson}, NOW(), NOW())
            ON DUPLICATE KEY UPDATE answers_json = VALUES(answers_json), update_time = NOW()
            """)
    int upsert(@Param("surveyId") Long surveyId,
               @Param("userId") Long userId,
               @Param("answersJson") String answersJson);
}
