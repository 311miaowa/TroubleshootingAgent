package cn.bugstack.ai;

import org.springframework.beans.factory.annotation.Configurable;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@Configurable
@EnableScheduling
public class Application {

    /**
     * 启动入口。
     * <p>
     * <b>必须把 {@code args} 交给 SpringApplication</b>：否则命令行参数（{@code --server.port=8098}、
     * {@code --spring.profiles.active=prod}、{@code --xfg.datasource.health.ttl-ms=5000} 等）
     * 会被<b>静默忽略</b>——不报错、不生效，只在现象上表现为"改了参数没反应"（实测踩过这个坑）。
     * 需要覆盖属性时，除了这里的 CLI 参数，也可用系统属性（{@code -Dkey=value}）、环境变量
     * 或 {@code SPRING_APPLICATION_JSON}。
     */
    public static void main(String[] args){
        SpringApplication.run(Application.class, args);
    }

}
