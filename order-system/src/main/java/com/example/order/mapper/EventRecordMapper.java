package com.example.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.order.entity.EventRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 事件记录 Mapper
 *
 * 继承 BaseMapper<EventRecord> 后自动拥有：
 *   insert、updateById、selectById、selectList 等单表操作
 *   不需要写任何 XML
 *
 * 状态流转统一走条件更新（手写 SQL）：多实例部署时谁先命中谁处理，绝不重复处理。
 *   ① claimForSend：WAIT→SENDING（抢占 + 写 claim_owner/claimed_at，影响行数当闸门）
 *   ② markSendSuccess：SENDING→SUCCESS（带 claim_owner 条件，旧实例不会覆盖新实例）
 *   ③ markSendResult：失败退避（SENDING→WAIT）或进死信（SENDING→FAIL）
 *   ④ reclaimStuck：回收崩溃实例遗留的 SENDING 僵尸
 *   ⑤ requeueFailed：人工把死信 FAIL 复位回 WAIT
 */
@Mapper
public interface EventRecordMapper extends BaseMapper<EventRecord> {

    /**
     * 条件抢占处理权：只有 status=WAIT 的才轮到本次处理，返回影响行数。
     * 抢占同时写上 claim_owner（谁在处理）和 claimed_at（领取时间）：
     * 多实例部署时两个调度器可能同时扫到同一条 WAIT 记录——
     * 谁先执行这条 UPDATE 谁影响行数=1，另一个=0，跳过，事件绝不重复处理。
     */
    @Update("UPDATE t_event_record SET status='SENDING', claim_owner=#{owner}, claimed_at=NOW() " +
            "WHERE id=#{id} AND status='WAIT'")
    int claimForSend(@Param("id") Long id, @Param("owner") String owner);

    /**
     * 回收僵尸 SENDING 记录：实例中途崩溃时记录卡在 SENDING 超过阈值，
     * 下个周期扫回来置回 WAIT（清掉领取痕迹），让其他实例补发。
     *
     * 判定基准必须是 claimed_at（领取时间）而不是 next_retry_time（入队/退避时间）：
     * next_retry_time 是事件进入待处理队列的时间，排了队才被领取的在途事件，
     * 其 next_retry_time 早已过期——用它判僵死会把"正在正常处理"的事件误回收。
     */
    @Update("UPDATE t_event_record SET status='WAIT', claim_owner=NULL " +
            "WHERE status='SENDING' AND claimed_at < #{before}")
    int reclaimStuck(@Param("before") LocalDateTime before);

    /**
     * 条件标记成功：只有还是 SENDING 且归属本实例才置 SUCCESS。
     * 带 claim_owner 条件，旧实例（已崩溃被回收、记录又被新实例抢占）补刀时命中 0 行，
     * 不会把新实例正在处理的记录错误改掉。
     */
    @Update("UPDATE t_event_record SET status='SUCCESS' " +
            "WHERE id=#{id} AND status='SENDING' AND claim_owner=#{owner}")
    int markSendSuccess(@Param("id") Long id, @Param("owner") String owner);

    /**
     * 失败后的条件状态流转：只有还是 SENDING 且归属本实例才允许写结果。
     * 退避 → status=WAIT + 递增 retry_count + next_retry_time；死信 → status=FAIL。
     */
    @Update("UPDATE t_event_record SET status=#{status}, retry_count=#{retryCount}, " +
            "next_retry_time=#{nextRetryTime}, error_msg=#{errorMsg} " +
            "WHERE id=#{id} AND status='SENDING' AND claim_owner=#{owner}")
    int markSendResult(@Param("id") Long id, @Param("owner") String owner,
                       @Param("status") String status, @Param("retryCount") Integer retryCount,
                       @Param("nextRetryTime") LocalDateTime nextRetryTime,
                       @Param("errorMsg") String errorMsg);

    /**
     * 人工重试入口：把死信（FAIL）复位回 WAIT，清掉重试计数/错误信息/领取痕迹，立即重新排队。
     */
    @Update("UPDATE t_event_record SET status='WAIT', retry_count=0, next_retry_time=NOW(), " +
            "claim_owner=NULL, error_msg=NULL " +
            "WHERE id=#{id} AND status='FAIL'")
    int requeueFailed(@Param("id") Long id);
}
