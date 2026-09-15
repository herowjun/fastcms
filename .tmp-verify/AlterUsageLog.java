import java.sql.*;

public class AlterUsageLog {
    public static void main(String[] args) throws Exception {
        String url = "jdbc:mysql://localhost:3308/fastcms?useSSL=false&characterEncoding=utf8&serverTimezone=Asia/Shanghai";
        try (Connection conn = DriverManager.getConnection(url, "root", "123456");
             Statement st = conn.createStatement()) {
            ResultSet rs = st.executeQuery("SHOW COLUMNS FROM ai_usage_log LIKE 'agent_id'");
            if (rs.next()) {
                System.out.println("agent_id 列已存在，跳过: " + rs.getString("Type"));
            } else {
                st.executeUpdate("ALTER TABLE ai_usage_log ADD COLUMN agent_id varchar(64) DEFAULT NULL COMMENT '归属智能体ID（builtin.*/custom-*，未走智能体为空）' AFTER session_id");
                System.out.println("已添加 agent_id 列");
            }
            rs = st.executeQuery("SHOW INDEX FROM ai_usage_log WHERE Key_name = 'idx_agent_created'");
            if (rs.next()) {
                System.out.println("idx_agent_created 索引已存在，跳过");
            } else {
                st.executeUpdate("ALTER TABLE ai_usage_log ADD INDEX idx_agent_created (agent_id, created)");
                System.out.println("已添加 idx_agent_created 索引");
            }
            rs = st.executeQuery("SHOW COLUMNS FROM ai_usage_log LIKE 'agent_id'");
            rs.next();
            System.out.println("最终确认: agent_id " + rs.getString("Type") + " nullable=" + rs.getString("Null"));
        }
    }
}
