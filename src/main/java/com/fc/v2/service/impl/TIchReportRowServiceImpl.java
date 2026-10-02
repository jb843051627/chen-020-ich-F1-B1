package com.fc.v2.service.impl;

import java.util.List;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.TIchReportRowMapper;
import com.fc.v2.model.auto.TIchReportRow;
import com.fc.v2.service.ITIchReportRowService;

/**
 * 履职情况报送核收行 Service业务层处理（batch-process 形状：整批提交）
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class TIchReportRowServiceImpl implements ITIchReportRowService {

    private static final int MAX_ROWS = 500;
    private static final int STATUS_OK = 1;
    private static final int STATUS_FAIL = 2;
    /** 单行明细码长度上限 */
    private static final int ITEM_CODE_MAX = 64;
    /** 单行数量上限（超出视为录入错误，防止脏数据拉爆统计） */
    private static final java.math.BigDecimal QTY_MAX = new java.math.BigDecimal("999999");

    /** 批次处理留痕（单机内存缓冲，供运维查"这批发过没有"） */
    private final java.util.Map<String, String> batchStats =
            new java.util.concurrent.ConcurrentHashMap<String, String>();

    @javax.annotation.Resource
    private TIchReportRowMapper ichReportRowMapper;

    @Override
    public TIchReportRow selectTIchReportRowById(Long id) {
        return this.ichReportRowMapper.selectById(id);
    }

    @Override
    @org.springframework.transaction.annotation.Transactional(
            rollbackFor = java.lang.Exception.class)
    public int submitBatch(String batchNo, List<TIchReportRow> rows) {
        // 空批次：没有东西可核，按 0 行接收，不插任何库（含 null 入参，兜底防 NPE/越界）
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        // 批号是把成功行、失败行归到同一本账的唯一线索；缺号整批不收
        if (isBlank(batchNo)) {
            return 0;
        }
        // 单批行数硬上限：超限整批不入库，返回 -1 由调用方改走拆分，绝不"来多少收多少"
        if (rows.size() > MAX_ROWS) {
            note(batchNo, "超过单批上限 " + MAX_ROWS + " 行，整批拒收（实际 " + rows.size() + " 行）");
            return -1;
        }
        // 重发幂等：同一批号只要落过库（成功或失败都算），直接回放上次成功行数，绝不再插第二份
        if (countBatch(batchNo) > 0) {
            int accepted = countAccepted(batchNo);
            note(batchNo, "批次重复提交，按已接收 " + accepted + " 行幂等返回");
            return accepted;
        }

        // 批号以入参为唯一口径，行内自带批号一律不采信，保证写入口/读出口前后同源
        String no = batchNo.trim();

        // 去重全批只认一把尺：(批号, 明细码)。先并入该批存量已落的码，再逐行占码，
        // 同一码只收第一行，其余记失败——存量与本批口径一致。
        java.util.Set<String> takenCodes = existingCodes(no);
        // 行号也全批共用一个已用池：成功行、失败行一起归一，互不撞号、不覆盖原报行次
        java.util.Set<Integer> usedRowNos = new java.util.HashSet<Integer>();

        int ok = 0;
        int position = 0;
        for (TIchReportRow r : rows) {
            if (r == null) {
                // 空位不是一行数据，跳过且不占行号、不计数
                continue;
            }
            position++;
            int rowNo = normalizeRowNo(r, usedRowNos, position);
            String reason = invalidReason(r);
            if (reason == null && !takenCodes.add(r.getItemCode().trim())) {
                reason = "批次内明细码重复：" + r.getItemCode().trim();
            }
            if (reason != null) {
                // 非法行进失败明细：状态置 2、原因写备注、原报行号保留，成功行照常入库
                markFail(r, no, rowNo, reason);
            } else {
                markOk(r, no, rowNo);
                ok++;
            }
        }
        int total = position;
        note(no, "提交完成：成功 " + ok + " 行，失败 " + (total - ok) + " 行");
        return ok;
    }

    @Override
    public List<TIchReportRow> listErrors(String batchNo) {
        if (isBlank(batchNo)) {
            return new java.util.ArrayList<TIchReportRow>();
        }
        // 只取该批次的失败明细（成功行从不进失败清单），口径与 submitBatch 的落库口径一致
        List<TIchReportRow> errs = this.ichReportRowMapper.selectList(new QueryWrapper<TIchReportRow>()
                .eq("batch_no", batchNo)
                .eq("status", STATUS_FAIL)
                .eq("del_flag", 0));
        if (errs == null || errs.isEmpty()) {
            return new java.util.ArrayList<TIchReportRow>();
        }
        // 按原始行号升序：运维要按 Excel 行序逐行修，乱序会漏改
        errs.sort(new java.util.Comparator<TIchReportRow>() {
            @Override
            public int compare(TIchReportRow a, TIchReportRow b) {
                int ra = a.getRowNo() == null ? 0 : a.getRowNo().intValue();
                int rb = b.getRowNo() == null ? 0 : b.getRowNo().intValue();
                return ra - rb;
            }
        });
        return errs;
    }
    /** 批次是否已落过明细（成功或失败都算），用于重放判定 */
    private int countBatch(String batchNo) {
        List<TIchReportRow> exist = this.ichReportRowMapper.selectList(
                new QueryWrapper<TIchReportRow>().eq("batch_no", batchNo));
        return exist == null ? 0 : exist.size();
    }

    /** 该批已接收（成功）行数：重发命中幂等时原样回放，口径与 submitBatch 返回值一致 */
    private int countAccepted(String batchNo) {
        List<TIchReportRow> accepted = this.ichReportRowMapper.selectList(new QueryWrapper<TIchReportRow>()
                .eq("batch_no", batchNo)
                .eq("status", STATUS_OK)
                .eq("del_flag", 0));
        return accepted == null ? 0 : accepted.size();
    }

    /**
     * 该批在库里已占用的明细码（存量并入时的去重底盘）。
     * 与批内逐行占码共用同一把尺：trim 后的 (批号, 明细码)，保证存量与本批一致。
     */
    private java.util.Set<String> existingCodes(String batchNo) {
        List<TIchReportRow> exist = this.ichReportRowMapper.selectList(new QueryWrapper<TIchReportRow>()
                .select("item_code")
                .eq("batch_no", batchNo)
                .eq("del_flag", 0));
        java.util.Set<String> codes = new java.util.HashSet<String>();
        if (exist == null) {
            return codes;
        }
        for (TIchReportRow r : exist) {
            if (!isBlank(r.getItemCode())) {
                codes.add(r.getItemCode().trim());
            }
        }
        return codes;
    }

    /** 空串判定：批次号/明细码的纯空白串按"缺失"处理 */
    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /** 单行校验：返回 null 表示合法；否则返回失败原因（原因写入失败明细备注，便于运维定位） */
    private String invalidReason(TIchReportRow r) {
        if (isBlank(r.getItemCode())) {
            return "明细码为空";
        }
        if (r.getItemCode().trim().length() > ITEM_CODE_MAX) {
            return "明细码超过 " + ITEM_CODE_MAX + " 个字符";
        }
        if (r.getQty() == null) {
            return "数量缺失";
        }
        if (r.getQty().compareTo(java.math.BigDecimal.ZERO) <= 0) {
            return "数量必须大于 0";
        }
        if (r.getQty().compareTo(QTY_MAX) > 0) {
            return "数量超过单行上限 " + QTY_MAX.toPlainString();
        }
        return null;
    }

    /** 原始行号归一：优先保留调用方给的 Excel 行号，缺失/冲突时才补一个未占用序号 */
    private static int normalizeRowNo(TIchReportRow r, java.util.Set<Integer> used, int fallback) {
        Integer given = r.getRowNo();
        if (given != null && given.intValue() > 0 && used.add(given)) {
            return given.intValue();
        }
        int candidate = fallback > 0 ? fallback : 1;
        while (!used.add(Integer.valueOf(candidate))) {
            candidate++;
        }
        return candidate;
    }

    /** 失败行落库：状态置失败 + 原因写进备注，原始行号原样保留 */
    private void markFail(TIchReportRow r, String batchNo, int rowNo, String reason) {
        r.setBatchNo(batchNo);
        r.setRowNo(Integer.valueOf(rowNo));
        r.setStatus(STATUS_FAIL);
        r.setRemark(reason);
        stamp(r);
        this.ichReportRowMapper.insert(r);
    }

    /** 成功行落库：状态置成功，并清掉失败备注（同一实体可能被复用，避免残留上次原因） */
    private void markOk(TIchReportRow r, String batchNo, int rowNo) {
        r.setBatchNo(batchNo);
        r.setRowNo(Integer.valueOf(rowNo));
        r.setStatus(STATUS_OK);
        r.setRemark(null);
        stamp(r);
        this.ichReportRowMapper.insert(r);
    }

    /** 写前盖章：软删标记缺省补 0，更新时间统一由服务端生成 */
    private static void stamp(TIchReportRow r) {
        if (r.getDelFlag() == null) {
            r.setDelFlag(0);
        }
        r.setUpdateTime(new java.util.Date());
    }

    /** 整批处理留痕：按批次记录最近一次处理结论（内存缓冲，超过 200 批整体清理） */
    private void note(String batchNo, String what) {
        if (batchStats.size() > 200) {
            batchStats.clear();
        }
        batchStats.put(batchNo, what);
    }

}
