package cn.ks.vms;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.net.InetAddress;

@Component
class Bootstrap implements ApplicationRunner {
    private final Db db;
    private final PasswordEncoder passwords;
    private final Crypto crypto;
    private final boolean demo;
    private final String initialPassword;
    private final String initialTotp;
    private final InetAddress bindAddress;

    Bootstrap(Db db, PasswordEncoder passwords, Crypto crypto, @Value("${vms.demo}") boolean demo,
              @Value("${vms.admin-password}") String initialPassword, @Value("${VMS_ADMIN_TOTP:}") String initialTotp,
              @Value("${server.address}") InetAddress bindAddress) {
        this.db = db;
        this.passwords = passwords;
        this.crypto = crypto;
        this.demo = demo;
        this.initialPassword = initialPassword;
        this.initialTotp = initialTotp;
        this.bindAddress = bindAddress;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments arguments) {
        if (demo && !bindAddress.isLoopbackAddress()) {
            throw new IllegalStateException("Demo mode requires a loopback address");
        }
        if (db.count("SELECT COUNT(*) FROM staff") != 0) {
            return;
        }
        if (!demo) {
            int length = initialPassword.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (length < 12 || length > 72) {
                throw new IllegalStateException("Set an initial VMS_ADMIN_PASSWORD from 12 to 72 bytes");
            }
            var id = createStaff("admin", "系统管理员", null, passwords.encode(initialPassword));
            db.update("UPDATE staff SET mfa_secret=? WHERE id=?", crypto.encryptAuthenticator(Totp.normalize(initialTotp)), id);
            grant(id, "ADMIN");
            return;
        }
        var password = passwords.encode("KS-VMS-demo-2026");
        long department = db.insert("INSERT INTO department(name) VALUES ('行政接待部')");
        long site = db.insert("INSERT INTO site(name) VALUES ('科晟科技 · 一号厂区')");
        long gate = db.insert("INSERT INTO gate(site_id,name) VALUES (?,'东门门岗')", site);
        db.update("INSERT INTO gate(site_id,name) VALUES (?,'西门门岗')", site);
        db.update("INSERT INTO area(site_id,name) VALUES (?,'接待中心'),(?,'生产作业区'),(?,'仓储收发区')", site, site, site);
        db.update("INSERT INTO registration_link(slug,site_id) VALUES ('company',?)", site);
        db.update("INSERT INTO registration_link(slug,site_id,gate_id) VALUES ('east-gate',?,?)", site, gate);
        db.update("INSERT INTO notice(site_id,kind,text) VALUES (?,'SAFETY',?)", site,
            "请在门岗核验证件，按接待人员引导访问指定区域。未经允许，请勿进入生产及设备作业区域。离厂时请在门岗办理签出。");
        db.update("INSERT INTO notice(site_id,kind,text) VALUES (?,'PRIVACY',?)", site,
            "本演示用于预约、核验和进出厂追溯。请填写虚构资料，门岗核验所用证件号码只保存身份摘要与尾号。");
        var admin = createStaff("admin", "林知远", department, password);
        for (String role : AdminController.ROLES) {
            if (!"SAFETY".equals(role)) {
                grant(admin, role);
            }
        }
        var security = createStaff("safety", "陈晓青", department, password);
        grant(security, "SAFETY");
        var guard = createStaff("guard", "周卫东", department, password);
        grant(guard, "GUARD");
        var host = createStaff("employee", "王嘉宁", department, password);
        grant(host, "EMPLOYEE");
        var boss = createStaff("approver", "许明", department, password);
        for (String role : java.util.List.of("HOST_SUPERVISOR", "OWNER", "PROCUREMENT", "WAREHOUSE",
            "LOGISTICS", "PROJECT", "EQUIPMENT", "HR", "IT")) {
            grant(boss, role);
        }
    }

    private long createStaff(String username, String name, Long department, String password) {
        return db.insert("""
            INSERT INTO staff(username,name,department_id,password_hash,host_public) VALUES (?,?,?,?,?)
            """, username, name, department, password, department != null && !"guard".equals(username));
    }

    private void grant(long id, String role) {
        db.update("INSERT INTO staff_grant(staff_id,role) VALUES (?,?)", id, role);
    }
}
