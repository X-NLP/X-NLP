package com.xnlp.server.waste;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.security.SecureRandom;

@Service
public class WasteApplicationService {

    private static final DateTimeFormatter NO_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final long AUTHORIZATION_CODE_MINUTES = 2;
    private static final SecureRandom RANDOM = new SecureRandom();
    private final JdbcTemplate jdbc;

    public WasteApplicationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void seedVehicles() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM waste_vehicles", Integer.class);
        if (count != null && count > 0) return;
        jdbc.update("INSERT INTO waste_vehicles (id, plate_no, vehicle_type, company_name, driver_name, driver_phone, verified) VALUES (?, ?, ?, ?, ?, ?, ?)",
                "v-001", "粤A·7K52Q", "重型自卸货车", "广州安运渣土运输有限公司", "李师傅", "13800138001", true);
        jdbc.update("INSERT INTO waste_vehicles (id, plate_no, vehicle_type, company_name, driver_name, driver_phone, verified) VALUES (?, ?, ?, ?, ?, ?, ?)",
                "v-002", "粤A·3M81P", "重型自卸货车", "广州城建运输有限公司", "王师傅", "13800138002", true);
        jdbc.update("INSERT INTO waste_vehicles (id, plate_no, vehicle_type, company_name, driver_name, driver_phone, verified) VALUES (?, ?, ?, ?, ?, ?, ?)",
                "v-003", "粤A·9R20L", "轻型自卸货车", "广州绿城清运有限公司", "陈师傅", "13800138003", true);
    }

    public List<Map<String, Object>> vehicles() {
        seedVehicles();
        return jdbc.queryForList("SELECT id, plate_no, vehicle_type, company_name, driver_name, driver_phone, verified FROM waste_vehicles WHERE verified = TRUE ORDER BY plate_no");
    }

    @Transactional
    public Map<String, Object> create(Map<String, Object> payload) {
        String id = UUID.randomUUID().toString();
        String applicationNo = "JS" + LocalDateTime.now().format(NO_FORMAT) + String.format("%03d", (int) (Math.random() * 1000));
        String vehicleId = required(payload, "vehicleId");
        jdbc.queryForMap("SELECT * FROM waste_vehicles WHERE id = ? AND verified = TRUE", vehicleId);
        double estimatedWeight = positiveNumber(payload, "estimatedWeight");
        validatePhotoUrls(payload);
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("""
                INSERT INTO waste_applications
                (id, application_no, waste_type, clear_reason, pickup_location, estimated_weight_tons, vehicle_id,
                 processing_site, route_description, order_subject, subject_name, contact_name, contact_phone,
                 photo_urls, status, remaining_weight_tons, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?)
                """, id, applicationNo, required(payload, "wasteType"), required(payload, "clearReason"),
                required(payload, "pickupLocation"), estimatedWeight, vehicleId, required(payload, "processingSite"),
                value(payload, "routeDescription"), required(payload, "orderSubject"), required(payload, "subjectName"),
                required(payload, "contactName"), required(payload, "contactPhone"), value(payload, "photoUrls"), estimatedWeight, now);
        addAudit(id, "SUBMIT", "申请提交", "申请人");
        return get(id);
    }

    public List<Map<String, Object>> applications(String status) {
        refreshExpiredAuthorizationCodes();
        seedVehicles();
        String sql = """
                SELECT a.*, v.plate_no, v.vehicle_type, v.company_name, v.driver_name, v.driver_phone
                FROM waste_applications a JOIN waste_vehicles v ON v.id = a.vehicle_id
                """;
        if (status != null && !status.isBlank()) return jdbc.queryForList(sql + " WHERE a.status = ? ORDER BY a.created_at DESC", status);
        return jdbc.queryForList(sql + " ORDER BY a.created_at DESC");
    }

    public Map<String, Object> get(String id) {
        refreshExpiredAuthorizationCodes();
        return jdbc.queryForMap("""
                SELECT a.*, v.plate_no, v.vehicle_type, v.company_name, v.driver_name, v.driver_phone
                FROM waste_applications a JOIN waste_vehicles v ON v.id = a.vehicle_id WHERE a.id = ?
                """, id);
    }

    @Transactional
    public Map<String, Object> review(String id, boolean approve, String reviewer, String comment) {
        Map<String, Object> application = get(id);
        if (!"PENDING".equals(application.get("status"))) throw new IllegalStateException("该申请已审核，不能重复处理");
        LocalDateTime now = LocalDateTime.now();
        if (approve) {
            String code = newAuthorizationCode();
            LocalDateTime expires = now.plusMinutes(AUTHORIZATION_CODE_MINUTES);
            jdbc.update("UPDATE waste_applications SET status = 'APPROVED', reviewer = ?, review_comment = ?, reviewed_at = ?, approved_at = ?, code = ?, code_expires_at = ? WHERE id = ?",
                    reviewer, comment, now, now, code, expires, id);
            addAudit(id, "APPROVE", "审核通过并生成 2 分钟动态授权码", reviewer);
        } else {
            jdbc.update("UPDATE waste_applications SET status = 'REJECTED', reviewer = ?, review_comment = ?, reviewed_at = ? WHERE id = ?",
                    reviewer, comment, now, id);
            addAudit(id, "REJECT", "审核驳回：" + (comment == null ? "未填写原因" : comment), reviewer);
        }
        return get(id);
    }

    @Transactional
    public Map<String, Object> refreshCode(String id) {
        Map<String, Object> application = get(id);
        if (!"APPROVED".equals(application.get("status"))) throw new IllegalStateException("仅审核通过的工单可以刷新授权码");
        String code = newAuthorizationCode();
        LocalDateTime expires = LocalDateTime.now().plusMinutes(AUTHORIZATION_CODE_MINUTES);
        jdbc.update("UPDATE waste_applications SET code = ?, code_expires_at = ? WHERE id = ?", code, expires, id);
        addAudit(id, "REFRESH_CODE", "刷新 2 分钟动态授权码", "申请人");
        return get(id);
    }

    @Transactional
    public Map<String, Object> verifyGate(String code, String plateNo) {
        refreshExpiredAuthorizationCodes();
        if (code == null || code.isBlank() || plateNo == null || plateNo.isBlank()) {
            return Map.of("allowed", false, "message", "验签失败：授权码和车牌不能为空");
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT a.*, v.plate_no, v.driver_name, v.company_name
                FROM waste_applications a JOIN waste_vehicles v ON v.id = a.vehicle_id
                WHERE a.code = ? AND v.plate_no = ? AND a.status = 'APPROVED' AND a.code_expires_at > CURRENT_TIMESTAMP
                """, code.trim(), plateNo.trim());
        if (rows.isEmpty()) return Map.of("allowed", false, "message", "验签失败：授权码、车牌或有效期不匹配");
        Map<String, Object> application = rows.getFirst();
        addAudit(String.valueOf(application.get("id")), "GATE_VERIFY", "门禁验签通过", "门禁设备");
        return Map.of("allowed", true, "message", "验签通过，抬杆放行", "application", application);
    }

    @Transactional
    public Map<String, Object> addWeighing(Map<String, Object> payload) {
        String applicationId = required(payload, "applicationId");
        Map<String, Object> application = get(applicationId);
        if (!"APPROVED".equals(application.get("status"))) throw new IllegalStateException("只有审核通过的申请才允许过磅");
        String eventType = required(payload, "eventType").toUpperCase();
        if (!"INBOUND".equals(eventType) && !"OUTBOUND".equals(eventType)) throw new IllegalArgumentException("eventType 必须是 INBOUND 或 OUTBOUND");
        double gross = nonNegativeNumber(payload, "grossWeight");
        double tare = nonNegativeNumber(payload, "tareWeight");
        if (gross < tare) throw new IllegalArgumentException("毛重不能小于皮重");
        double net = gross - tare;
        int inboundCount = countWeighings(applicationId, "INBOUND");
        int outboundCount = countWeighings(applicationId, "OUTBOUND");
        if (eventType.equals("INBOUND") && inboundCount != outboundCount) {
            throw new IllegalStateException("上一趟运输尚未完成出场过磅，不能重复进场");
        }
        int tripNo = outboundCount + 1;
        if (eventType.equals("OUTBOUND")) {
            if (inboundCount <= outboundCount) throw new IllegalStateException("出场过磅前必须先完成本趟进场过磅");
            double remaining = number(application, "remaining_weight_tons");
            if (net > remaining + 0.000001) throw new IllegalArgumentException("本次出场净重不能超过工单剩余重量");
        }
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO waste_weighings (id, application_id, application_no, plate_no, event_type, trip_no, gross_weight,
                tare_weight, net_weight, weighbridge_no, operator_name, weighed_at, source)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, applicationId, application.get("application_no"), application.get("plate_no"), eventType, tripNo,
                gross, tare, net, value(payload, "weighbridgeNo"), value(payload, "operatorName"), LocalDateTime.now(), "MANUAL");
        double consumed = jdbc.queryForObject("SELECT COALESCE(SUM(net_weight), 0) FROM waste_weighings WHERE application_id = ? AND event_type = 'OUTBOUND'", Double.class);
        double remaining = Math.max(0, number(application, "estimated_weight_tons") - consumed);
        if (remaining <= 0.000001) {
            jdbc.update("UPDATE waste_applications SET remaining_weight_tons = 0, status = 'COMPLETED', code = NULL, code_expires_at = NULL WHERE id = ?", applicationId);
            addAudit(applicationId, "COMPLETE", "累计出场净重达到预估重量，工单自动关闭并使授权码失效", "系统");
        } else {
            jdbc.update("UPDATE waste_applications SET remaining_weight_tons = ? WHERE id = ?", remaining, applicationId);
        }
        addAudit(applicationId, "WEIGH_" + eventType, "第" + tripNo + "趟" + (eventType.equals("INBOUND") ? "进场过磅" : "出场过磅"), value(payload, "operatorName"));
        return jdbc.queryForMap("SELECT * FROM waste_weighings WHERE id = ?", id);
    }

    public List<Map<String, Object>> ledger(String plateNo, String eventType) {
        StringBuilder sql = new StringBuilder("SELECT w.*, a.waste_type, a.processing_site, a.estimated_weight_tons FROM waste_weighings w JOIN waste_applications a ON a.id = w.application_id WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (plateNo != null && !plateNo.isBlank()) { sql.append(" AND w.plate_no LIKE ?"); args.add("%" + plateNo.trim() + "%"); }
        if (eventType != null && !eventType.isBlank()) { sql.append(" AND w.event_type = ?"); args.add(eventType.trim().toUpperCase()); }
        sql.append(" ORDER BY w.weighed_at DESC");
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    public Map<String, Object> dashboard() {
        refreshExpiredAuthorizationCodes();
        return jdbc.queryForMap("""
                SELECT
                  (SELECT COUNT(*) FROM waste_applications) AS total_applications,
                  (SELECT COUNT(*) FROM waste_applications WHERE status = 'PENDING') AS pending_applications,
                  (SELECT COUNT(*) FROM waste_applications WHERE status = 'APPROVED') AS approved_applications,
                  (SELECT COUNT(*) FROM waste_weighings WHERE event_type = 'INBOUND') AS inbound_count,
                  (SELECT COUNT(*) FROM waste_weighings WHERE event_type = 'OUTBOUND') AS outbound_count,
                  (SELECT COALESCE(SUM(net_weight), 0) FROM waste_weighings WHERE event_type = 'OUTBOUND') AS total_cleared_tons
                """);
    }

    public List<Map<String, Object>> audits(String id) {
        return jdbc.queryForList("SELECT action, description, operator_name, created_at FROM waste_audits WHERE application_id = ? ORDER BY created_at ASC", id);
    }

    private int countWeighings(String applicationId, String eventType) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM waste_weighings WHERE application_id = ? AND event_type = ?", Integer.class, applicationId, eventType);
        return count == null ? 0 : count;
    }

    private void addAudit(String applicationId, String action, String description, String operator) {
        jdbc.update("INSERT INTO waste_audits (id, application_id, action, description, operator_name, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                UUID.randomUUID().toString(), applicationId, action, description, operator == null ? "系统" : operator, LocalDateTime.now());
    }

    @Transactional
    public void refreshExpiredAuthorizationCodes() {
        List<String> expiredIds = jdbc.queryForList(
                "SELECT id FROM waste_applications WHERE status = 'APPROVED' AND code IS NOT NULL AND code_expires_at <= CURRENT_TIMESTAMP",
                String.class);
        for (String applicationId : expiredIds) {
            LocalDateTime now = LocalDateTime.now();
            jdbc.update("UPDATE waste_applications SET code = ?, code_expires_at = ? WHERE id = ? AND status = 'APPROVED' AND code_expires_at <= CURRENT_TIMESTAMP",
                    newAuthorizationCode(), now.plusMinutes(AUTHORIZATION_CODE_MINUTES), applicationId);
            addAudit(applicationId, "AUTO_REFRESH_CODE", "授权码到期自动轮换，旧码立即失效", "系统");
        }
    }

    private static void validatePhotoUrls(Map<String, Object> payload) {
        String photos = value(payload, "photoUrls");
        if (photos.isBlank()) throw new IllegalArgumentException("现场照片至少需要 2 张");
        long count = java.util.Arrays.stream(photos.split("[,\n;]")).map(String::trim).filter(v -> !v.isBlank()).count();
        if (count < 2) throw new IllegalArgumentException("现场照片至少需要 2 张");
    }

    private static String newAuthorizationCode() { return String.format("%06d", RANDOM.nextInt(1_000_000)); }
    private static String required(Map<String, Object> p, String key) { String value = value(p, key); if (value.isBlank()) throw new IllegalArgumentException(key + " 不能为空"); return value; }
    private static String value(Map<String, Object> p, String key) { Object value = p.get(key); return value == null ? "" : String.valueOf(value).trim(); }
    private static double positiveNumber(Map<String, Object> p, String key) { double value = number(p, key); if (value <= 0) throw new IllegalArgumentException(key + " 必须大于 0"); return value; }
    private static double nonNegativeNumber(Map<String, Object> p, String key) { double value = number(p, key); if (value < 0) throw new IllegalArgumentException(key + " 不能为负数"); return value; }
    private static double number(Map<String, Object> p, String key) { String value = required(p, key); try { return Double.parseDouble(value); } catch (NumberFormatException e) { throw new IllegalArgumentException(key + " 必须是数字"); } }
}
