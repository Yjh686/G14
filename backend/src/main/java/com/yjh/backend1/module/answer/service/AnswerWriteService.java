package com.yjh.backend1.module.answer.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yjh.backend1.module.answer.entity.AnswerItem;
import com.yjh.backend1.module.answer.entity.AnswerSheet;
import com.yjh.backend1.module.answer.entity.SurveyDraft;
import com.yjh.backend1.module.answer.mapper.AnswerItemMapper;
import com.yjh.backend1.module.answer.mapper.AnswerSheetMapper;
import com.yjh.backend1.module.log.service.OperationLogService;
import com.yjh.backend1.module.survey.entity.Survey;
import com.yjh.backend1.module.survey.mapper.SurveyMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 答卷写入。
 * <p>
 * <b>为什么单独抽一个 Bean：</b>Spring 的声明式事务基于代理，同类内部调用不经过代理，
 * 把 {@code @Transactional} 方法写在 {@code AnswerService} 里再自己调用等于没写。
 * <p>
 * <b>为什么整个提交必须是「一个」事务（1 次 commit）：</b>
 * 这是 2026-11-09 压测逐层剥出来的结论，写在这里免得以后有人再拆开。
 * <ol>
 *   <li>先测出这台机器的硬上限：单行 {@code UPDATE ... ; commit} 不管开 1 个还是 32 个线程，
 *       都只有约 170 次/秒，且延迟随线程线性增长（32 线程 183ms）——
 *       说明每次 commit 要等一次磁盘 fsync（约 6ms），是串行的。</li>
 *   <li>原先一次提交要付 <b>3 次</b> commit（答卷入库 / 更新回收计数 / 写操作日志），
 *       理论上限就是 170 ÷ 3 ≈ 57 次/秒，实测 100 并发下 28 次/秒、
 *       P95 3687ms —— 与推算吻合。</li>
 *   <li>合并成 1 次 commit 后上限回到 170 次/秒，延迟由「排队等 3 次 fsync」变成「等 1 次」。</li>
 * </ol>
 * 反直觉的地方是：合并反而<b>不会</b>让热点行锁变慢。InnoDB 的行锁本来就要持有到提交成功，
 * 而提交成功的耗时就是那次 fsync —— 拆成两个事务时，锁在第一个事务释放、第二个事务再拿回来，
 * 中间白付一次 commit 的钱，锁的持有总时长并没有变短。
 * <p>
 * 唯一真正的代价：`answer_count` 那行在事务期间被锁着，同一份问卷的并发提交会在这行上排队。
 * 但排队的长度是「1 次 fsync」而不是「整个业务处理」，因为事务里其余语句都是主键/索引写入，亚毫秒级。
 * 如果哪天答卷明细大幅变多（比如 100 题），就应该把明细写入拆出去、只把计数和答卷头放一个事务 ——
 * 那时瓶颈会从 fsync 变成锁持有时间。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnswerWriteService {

    private final AnswerSheetMapper sheetMapper;
    private final AnswerItemMapper itemMapper;
    private final SurveyMapper surveyMapper;
    private final OperationLogService operationLogService;

    /**
     * 提交一份答卷：写答卷头 + 明细 + 更新回收计数 + 写操作日志，合为一个事务。
     * <p>
     * 草稿作废<b>不在</b>本事务内（见方法体注释），由调用方在提交成功后单独清理。
     *
     * @param logDetail 非空则写操作日志；匿名问卷传 null（日志里有操作人，
     *                  「某人提交了某匿名问卷」可反推身份，与权限矩阵的匿名约束冲突）
     * @throws DuplicateKeyException 并发提交撞上唯一索引（调用方转成 409）
     */
    @Transactional
    public AnswerSheet commit(Long surveyId, Long userId, boolean limitOnce,
                              List<AnswerItem> items, String surveyTitle,
                              String logDetail, HttpServletRequest httpReq) {
        AnswerSheet sheet = new AnswerSheet();
        sheet.setSurveyId(surveyId);
        sheet.setUserId(userId);
        sheet.setLimitOnce(limitOnce ? 1 : 0);
        sheet.setIsValid(1);
        sheetMapper.insert(sheet);

        for (AnswerItem it : items) {
            it.setSheetId(sheet.getId());
            it.setSurveyId(surveyId);
            itemMapper.insert(it);
        }

        // 注意：这里<b>不</b>删草稿。
        //
        // 原先这里有一句 DELETE FROM survey_draft WHERE survey_id=? AND user_id=?。
        // 压测暴露出它会和「草稿保存」的 INSERT ... ON DUPLICATE KEY UPDATE 互相死锁。
        // 关键在于死锁双方<b>根本不是同一行</b>：InnoDB 的 DELETE 在唯一索引上取 next-key 锁
        // （记录锁 + 前面的间隙），并发的 INSERT 要对同一段间隙取插入意向锁，两者互斥；
        // 100 个学生同时在填同一份问卷时，(159,559)、(159,560)… 这些相邻记录天天撞在一起。
        // 死锁被打断的往往是那次草稿保存 —— 用户只是「刚交完就点开下一份」，什么都没做错。
        //
        // 而删草稿并不属于「提交成功」的语义，它只是顺手清理。
        // 所以移出事务：调用方在事务提交后单独删（AnswerService.submit → cleanupDraft），
        // 草稿表的所有写入再由 AnswerService 里按问卷分段的锁串起来，
        // 从根上不让「提交」和「存草稿」同时持有 draft 表的锁。
        // 顺带也少了一次 draft 表写入，虽然那部分开销被合并进同一次 fsync，不改变 commit 次数。

        // 有效答卷计数：用 SQL 表达式自增，不做「读出来加一写回去」
        surveyMapper.update(null, Wrappers.<Survey>lambdaUpdate()
                .eq(Survey::getId, surveyId)
                .setSql("answer_count = GREATEST(answer_count + 1, 0)"));

        if (logDetail != null) {
            // OperationLogService.write 自身没有 @Transactional，
            // 因此会加入当前事务，不额外产生 commit
            operationLogService.write("答卷管理", "提交答卷", "answer_sheet", sheet.getId(),
                    logDetail, httpReq);
        }

        return sheet;
    }

    /**
     * 调整 {@code survey.answer_count}（语义 = 有效答卷数），用于标记无效/恢复/删除答卷。
     * <p>
     * 这些操作本身还伴随答卷状态更新与日志写入，都在调用方的事务里；
     * 本方法不加 {@code REQUIRES_NEW}，避免把一次业务操作拆成两次 fsync。
     */
    @Transactional
    public void bumpAnswerCount(Long surveyId, int delta) {
        surveyMapper.update(null, Wrappers.<Survey>lambdaUpdate()
                .eq(Survey::getId, surveyId)
                .setSql("answer_count = GREATEST(answer_count + (" + delta + "), 0)"));
    }
}
