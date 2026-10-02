package com.fc.v2.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.fc.v2.mapper.auto.TIchReportRowMapper;
import com.fc.v2.model.auto.TIchReportRow;

/**
 * 履职情况报送核收行——针对交账前列出的那批毛病逐条回归。
 * 不连库：用内存假表模拟 selectList/insert，并按 QueryWrapper 的等值条件过滤。
 */
public class TIchReportRowServiceImplTest {

    private TIchReportRowServiceImpl service;
    private TIchReportRowMapper mapper;

    /** 内存假表 */
    private final List<TIchReportRow> table = new ArrayList<>();
    private int insertCount = 0;

    private static final Pattern COND =
            Pattern.compile("([a-z_]+)\\s*=\\s*#\\{ew\\.paramNameValuePairs\\.(MPGENVAL\\d+)\\}");

    @BeforeEach
    void setUp() {
        table.clear();
        insertCount = 0;
        service = new TIchReportRowServiceImpl();
        mapper = Mockito.mock(TIchReportRowMapper.class);
        ReflectionTestUtils.setField(service, "ichReportRowMapper", mapper);

        when(mapper.insert(any(TIchReportRow.class))).thenAnswer(inv -> {
            table.add(inv.getArgument(0));
            insertCount++;
            return 1;
        });
        when(mapper.selectList(any())).thenAnswer(inv -> selectFromFake(
                (com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?>) inv.getArgument(0)));
    }

    /** 解析 QueryWrapper 里的等值条件，在内存假表上做过滤（batch_no/status/del_flag）。 */
    private List<TIchReportRow> selectFromFake(
            com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?> w) {
        Map<String, Object> cond = new LinkedHashMap<>();
        Matcher m = COND.matcher(w.getSqlSegment());
        Map<?, ?> params = w.getParamNameValuePairs();
        while (m.find()) {
            cond.put(m.group(1), params.get(m.group(2)));
        }
        List<TIchReportRow> hit = new ArrayList<>();
        for (TIchReportRow r : table) {
            if (cond.containsKey("batch_no")
                    && !Objects.equals(r.getBatchNo(), cond.get("batch_no"))) {
                continue;
            }
            if (cond.containsKey("status")
                    && !Objects.equals(r.getStatus(), asInt(cond.get("status")))) {
                continue;
            }
            if (cond.containsKey("del_flag")
                    && !Objects.equals(r.getDelFlag(), asInt(cond.get("del_flag")))) {
                continue;
            }
            hit.add(r);
        }
        return hit;
    }

    private static Integer asInt(Object o) {
        return o == null ? null : ((Number) o).intValue();
    }

    private static TIchReportRow row(String code, String qty, Integer rowNo) {
        TIchReportRow r = new TIchReportRow();
        r.setItemCode(code);
        if (qty != null) {
            r.setQty(new BigDecimal(qty));
        }
        r.setRowNo(rowNo);
        return r;
    }

    private long countStatus(int status) {
        return table.stream().filter(r -> r.getStatus() == status).count();
    }

    /** 毛病1：一批里只有一条不合规，不该整批进不了库——合法行照收，非法行进失败明细。 */
    @Test
    void oneBadRowDoesNotRejectWholeBatch() {
        List<TIchReportRow> rows = new ArrayList<>();
        rows.add(row("CODE_A", "0", 1));      // 数量必须 >0，非法
        rows.add(row("CODE_B", "5", 2));      // 合法
        int ok = service.submitBatch("B1", rows);

        assertEquals(1, ok, "应只收下合法的 1 行");
        assertEquals(2, table.size(), "非法行也要落失败明细，共 2 行");
        assertEquals(1, countStatus(1));
        assertEquals(1, countStatus(2));

        List<TIchReportRow> errs = service.listErrors("B1");
        assertEquals(1, errs.size());
        assertTrue(errs.get(0).getRemark().contains("数量"), "失败原因要写进备注：" + errs.get(0).getRemark());
    }

    /** 毛病2：出错行号不能被重新编号——原报行次必须原样留得住，listErrors 才对得上 Excel。 */
    @Test
    void originalRowNoIsPreservedForFailures() {
        List<TIchReportRow> rows = new ArrayList<>();
        rows.add(row("OK1", "3", 10));
        rows.add(row("BAD", "0", 20));        // 给的是 Excel 第 20 行
        service.submitBatch("B2", rows);

        List<TIchReportRow> errs = service.listErrors("B2");
        assertEquals(1, errs.size());
        assertEquals(Integer.valueOf(20), errs.get(0).getRowNo(), "失败行必须保留原报行号 20");
    }

    /** 毛病3：重发一回同一批不能生出两份——已提交即幂等回放，不再插库。 */
    @Test
    void resubmitSameBatchIsIdempotent() {
        service.submitBatch("B3", java.util.Collections.singletonList(row("C1", "2", 1)));
        int second = service.submitBatch("B3",
                java.util.Collections.singletonList(row("C1", "2", 1)));

        assertEquals(1, second, "重发应回放已接收行数 1");
        assertEquals(1, table.size(), "库里仍只有 1 行，不得生第二份");
        assertEquals(1, insertCount, "幂等命中后不应再 insert");
    }

    /** 毛病4：行数得有上限——超过 500 整批不收，返回 -1。 */
    @Test
    void overLimitRejectsWholeBatch() {
        List<TIchReportRow> rows = new ArrayList<>();
        for (int i = 0; i < 501; i++) {
            rows.add(row("C" + i, "1", i + 1));
        }
        int r = service.submitBatch("B4", rows);
        assertEquals(-1, r);
        assertTrue(table.isEmpty(), "超限整批不入库");
    }

    /** 毛病5：明细空着/夹空位要有兜底——null 入参、空表返回 0；null 元素跳过不崩。 */
    @Test
    void emptyAndNullDetailsAreHandled() {
        assertEquals(0, service.submitBatch("B5", null));
        assertEquals(0, service.submitBatch("B5", new ArrayList<>()));

        List<TIchReportRow> rows = new ArrayList<>();
        rows.add(null);                        // 空位
        rows.add(row("ONLY", "9", 7));
        int ok = service.submitBatch("B5", rows);
        assertEquals(1, ok);
        assertEquals(1, table.size(), "空位跳过，只落 1 行");
        assertEquals(Integer.valueOf(7), table.get(0).getRowNo());
    }

    /** 毛病6：批内同码明细不能重复落库——同码第一行收，其余记失败。 */
    @Test
    void duplicateItemCodeWithinBatchRejected() {
        List<TIchReportRow> rows = new ArrayList<>();
        rows.add(row("DUP", "1", 1));
        rows.add(row("DUP", "2", 2));          // 同码再来一行
        int ok = service.submitBatch("B6", rows);

        assertEquals(1, ok);
        assertEquals(2, table.size());
        TIchReportRow second = table.get(1);
        assertEquals(Integer.valueOf(2), second.getStatus());
        assertTrue(second.getRemark().contains("重复"), "重复码要标明原因：" + second.getRemark());
    }

    /** 毛病7：批号前后同源——以入参 batchNo 为准，行内自带的别的批号一律不采信。 */
    @Test
    void batchNoComesFromArgumentNotRow() {
        TIchReportRow r = row("E1", "1", 1);
        r.setBatchNo("SOME_OTHER_NO");
        service.submitBatch("B7", java.util.Collections.singletonList(r));
        assertEquals("B7", table.get(0).getBatchNo());
    }

    /** 毛病8：回滚/超时要让写入口与读出口状态接得上——submitBatch 必须带事务且对 Exception 回滚。 */
    @Test
    void submitBatchIsTransactionalRollingBackOnException() throws Exception {
        Transactional tx = TIchReportRowServiceImpl.class
                .getMethod("submitBatch", String.class, List.class)
                .getAnnotation(Transactional.class);
        assertNotNull(tx, "submitBatch 必须声明 @Transactional");
        boolean rollsOnException = false;
        for (Class<?> c : tx.rollbackFor()) {
            if (c.isAssignableFrom(Exception.class)) {
                rollsOnException = true;
            }
        }
        assertTrue(rollsOnException, "须对 Exception（含超时类）回滚，保证两出口状态一致");
    }
}
