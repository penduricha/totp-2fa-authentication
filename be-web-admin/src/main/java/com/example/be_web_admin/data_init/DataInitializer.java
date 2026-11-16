package com.example.be_web_admin.data_init;

import com.example.be_web_admin.model.jpa.Admin;
import com.example.be_web_admin.model.jpa.Course;
import com.example.be_web_admin.repository.jpa.AdminRepository;
import com.example.be_web_admin.repository.jpa.CourseRepository;
import com.example.be_web_admin.security.password.MgmtPassword;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

@Component
public class DataInitializer {

    private final JdbcTemplate jdbcTemplate;

    private final AdminRepository adminRepository;

    private final CourseRepository courseRepository;

    private final String tableAdmin = "ntt_admins";

    private final String tableCourse = "ntt_courses";

    public DataInitializer(JdbcTemplate jdbcTemplate, AdminRepository adminRepository, CourseRepository courseRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.adminRepository = adminRepository;
        this.courseRepository = courseRepository;
    }

    public String generateHash(String rawText, int strength) {
        MgmtPassword mgmtPassword = new MgmtPassword();
        return mgmtPassword.generateHash(rawText, strength);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initializeData() throws JpaSystemException {

        // set utf-8
        List<String> codeSQLSetUtf8List = List.of(
                "ALTER TABLE " + tableAdmin + " CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci",
                "ALTER TABLE " + tableCourse + " CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci"
        );

        for (String sqlStatement : codeSQLSetUtf8List) {
            try {
                jdbcTemplate.execute(sqlStatement);
            } catch (Exception e) {
                System.err.println("Error executing: " + sqlStatement + " - " + e.getMessage());
            }
        }

        Integer countCourse = jdbcTemplate.queryForObject("select count(*) from " + tableCourse, Integer.class);
        Integer countAdmin = jdbcTemplate.queryForObject("select count(*) from " + tableAdmin, Integer.class);

        // Chỉ chèn data mẫu khi cả 2 bảng đều đang trống
        if ((countCourse == null || countCourse == 0) && (countAdmin == null || countAdmin == 0)) {

            // BCrypt strength 12 khá chậm -> hash 1 lần rồi dùng lại cho tất cả admin mẫu
            String defaultPasswordHash = generateHash("1234", 12);

            // ===================== COURSE 1 =====================
            Course course1 = new Course();
            course1.setCourseId(8480201L);
            course1.setTrainingSystem("Master");
            course1.setBranch("An Phu Dong ward, Ho Chi Minh city.");
            course1.setIndustry("AI (MLOPS)");
            course1.setFaculty("CNTT");
            course1.setMajor("AI/ML");
            course1.setTypeOfTraining("Research");
            course1.setYearOpening(2025);

            addAdmin(course1, 2500021771L, defaultPasswordHash, "Từ Quang Nhật", true,
                    "060203000127", "467/134/20 QL1A, An Phú Đông, Tphcm.", LocalDate.of(2003, 12, 25));
            addAdmin(course1, 2500021772L, defaultPasswordHash, "Lương Lưu Thanh Tú", true,
                    "060203000137", "467/134/2 QL1A, An Phú Đông, Tphcm.", LocalDate.of(2003, 9, 8));
            addAdmin(course1, 2500021773L, defaultPasswordHash, "Nguyễn Minh Anh", false,
                    "060203000147", "12 Lê Văn Khương, Thới An, Tphcm.", LocalDate.of(2002, 3, 14));
            addAdmin(course1, 2500021774L, defaultPasswordHash, "Trần Quốc Bảo", true,
                    "060203000157", "88 Nguyễn Ảnh Thủ, Hiệp Thành, Tphcm.", LocalDate.of(2001, 7, 21));
            addAdmin(course1, 2500021775L, defaultPasswordHash, "Phạm Thị Hồng Nhung", false,
                    "060203000167", "35 Tô Ký, Đông Hưng Thuận, Tphcm.", LocalDate.of(2002, 11, 5));

            // ===================== COURSE 2 =====================
            Course course2 = new Course();
            course2.setCourseId(8480101L);
            course2.setTrainingSystem("Master");
            course2.setBranch("An Phu Dong ward, Ho Chi Minh city.");
            course2.setIndustry("Computer Science");
            course2.setFaculty("CNTT");
            course2.setMajor("Khoa học máy tính");
            course2.setTypeOfTraining("Application");
            course2.setYearOpening(2026);

            addAdmin(course2, 2600021781L, defaultPasswordHash, "Lê Hoàng Long", true,
                    "070203000211", "21 Quang Trung, Gò Vấp, Tphcm.", LocalDate.of(2002, 1, 10));
            addAdmin(course2, 2600021782L, defaultPasswordHash, "Võ Thị Mai", false,
                    "070203000222", "54 Phan Văn Trị, Bình Thạnh, Tphcm.", LocalDate.of(2003, 4, 2));
            addAdmin(course2, 2600021783L, defaultPasswordHash, "Đặng Văn Khải", true,
                    "070203000233", "9 Nguyễn Oanh, Gò Vấp, Tphcm.", LocalDate.of(2001, 8, 19));
            addAdmin(course2, 2600021784L, defaultPasswordHash, "Hoàng Gia Hân", false,
                    "070203000244", "102 Lê Đức Thọ, Gò Vấp, Tphcm.", LocalDate.of(2003, 6, 30));
            addAdmin(course2, 2600021785L, defaultPasswordHash, "Bùi Anh Tuấn", true,
                    "070203000255", "7 Dương Quảng Hàm, Gò Vấp, Tphcm.", LocalDate.of(2002, 12, 1));

            // Course -> Admin phải có cascade (CascadeType.ALL/PERSIST) thì lưu course là lưu luôn admin
            courseRepository.saveAll(List.of(course1, course2));
        }
    }

    /**
     * Tạo admin và gắn 2 chiều với course (giữ đồng bộ cả hai phía quan hệ).
     */
    private void addAdmin(Course course, Long adminId, String passwordHash, String name, boolean gender,
                          String idCard, String address, LocalDate dateOfBirth) {
        Admin admin = new Admin();
        admin.setAdminId(adminId);
        admin.setPassword(passwordHash);
        admin.setAdminName(name);
        admin.setGender(gender);
        admin.setIdCard(idCard);
        admin.setAddress(address);
        admin.setDateOfBirthAdmin(dateOfBirth);

        course.getAdmins().add(admin);
        admin.setCourse(course);
    }
}