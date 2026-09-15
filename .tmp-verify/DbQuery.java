import java.sql.*;

public class DbQuery {
    public static void main(String[] args) throws Exception {
        String url = "jdbc:mysql://localhost:3308/fastcms?useSSL=false&characterEncoding=utf8&serverTimezone=Asia/Shanghai";
        try (Connection conn = DriverManager.getConnection(url, "root", "123456");
             Statement st = conn.createStatement()) {
            System.out.println("== 删除前 permission id=45 ==");
            ResultSet rs = st.executeQuery("SELECT id, name, path FROM permission WHERE id = 45");
            while (rs.next()) System.out.println(rs.getString(1) + " : " + rs.getString(2) + " : " + rs.getString(3));
            System.out.println("== role_permission 引用 ==");
            rs = st.executeQuery("SELECT COUNT(*) FROM role_permission WHERE permission_id = 45");
            rs.next();
            System.out.println("引用数: " + rs.getInt(1));

            int d1 = st.executeUpdate("DELETE FROM role_permission WHERE permission_id = 45");
            int d2 = st.executeUpdate("DELETE FROM permission WHERE id = 45");
            System.out.println("已删除 role_permission: " + d1 + " 行, permission: " + d2 + " 行");

            rs = st.executeQuery("SELECT COUNT(*) FROM permission WHERE id = 45");
            rs.next();
            System.out.println("删除后残留: " + rs.getInt(1));
        }
    }
}
