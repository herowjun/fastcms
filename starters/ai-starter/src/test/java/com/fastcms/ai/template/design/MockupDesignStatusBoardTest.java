package com.fastcms.ai.template.design;

import com.fastcms.ai.template.AiTemplateConstants;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 并行设计段状态聚合板行为验证：
 * 文案格式（进度计数 + 在途页清单）、顺序保持、重复 pageFinished 防重计数
 */
class MockupDesignStatusBoardTest {

    /** 收集型 SSE 桩：记录全部 status 事件 */
    private static final class RecordingSink implements DesignSseSink {
        final List<String> statuses = new ArrayList<>();

        @Override
        public void send(String eventName, String data) {
            if (AiTemplateConstants.SSE_EVENT_STATUS.equals(eventName)) {
                statuses.add(data);
            }
        }

        @Override
        public boolean isCancelled() {
            return false;
        }
    }

    @Test
    void aggregatesProgressAndActivePages() {
        RecordingSink sink = new RecordingSink();
        MockupDesignService.ParallelDesignStatusBoard board =
                new MockupDesignService.ParallelDesignStatusBoard(sink, 3);

        board.pageStarted("article", "文章页");
        assertThat(sink.statuses).last().isEqualTo("并行设计（0/3 完成）：文章页…");

        board.pageStarted("docs", "文档页");
        assertThat(sink.statuses).last().isEqualTo("并行设计（0/3 完成）：文章页、文档页…");

        board.pageFinished("article");
        assertThat(sink.statuses).last().isEqualTo("并行设计（1/3 完成）：文档页…");

        board.pageStarted("market", "市场页");
        assertThat(sink.statuses).last().isEqualTo("并行设计（1/3 完成）：文档页、市场页…");

        board.pageFinished("docs");
        board.pageFinished("market");
        assertThat(sink.statuses).last().isEqualTo("并行设计（3/3 完成）：…");
    }

    @Test
    void duplicateFinishDoesNotDoubleCount() {
        RecordingSink sink = new RecordingSink();
        MockupDesignService.ParallelDesignStatusBoard board =
                new MockupDesignService.ParallelDesignStatusBoard(sink, 2);

        board.pageStarted("a", "页A");
        board.pageFinished("a");
        board.pageFinished("a"); // 重复结束（防御路径）：不应把 done 推到 2
        assertThat(sink.statuses).last().isEqualTo("并行设计（1/2 完成）：…");
    }
}
