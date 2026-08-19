package cn.twopair.logging;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.RollingFileAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.junit.Assert;
import org.junit.Test;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * @author ljj
 * @description 验证日志级别、控制台输出和按日持久化配置。
 * @date 2026/8/19
 * @twopair
 */
public class LoggingConfigurationTest {

	/**
	 * 验证Log4j2运行时加载了项目DEBUG、根INFO和滚动文件Appender。
	 */
	@Test
	public void testRuntimeLoggingConfiguration() {
		LoggerContext context = (LoggerContext) LogManager.getContext(false);
		Configuration configuration = context.getConfiguration();
		LoggerConfig projectLogger = configuration.getLoggerConfig("cn.twopair.server.handler.CommandHandler");

		Assert.assertEquals("cn.twopair", projectLogger.getName());
		Assert.assertEquals(Level.DEBUG, projectLogger.getLevel());
		Assert.assertEquals(Level.INFO, configuration.getRootLogger().getLevel());
		Assert.assertNotNull(configuration.getAppender("Console"));
		Assert.assertTrue(configuration.getAppender("DailyFile") instanceof RollingFileAppender);

		RollingFileAppender fileAppender = (RollingFileAppender) configuration.getAppender("DailyFile");
		Assert.assertTrue(fileAppender.getFilePattern().contains("%d{yyyy-MM-dd}"));
	}

	/**
	 * 验证日志配置包含每日滚动、日期文件名和30天清理策略。
	 *
	 * @throws Exception 当配置资源读取失败时抛出
	 */
	@Test
	public void testDailyRollingAndRetentionConfiguration() throws Exception {
		URL resource = Thread.currentThread().getContextClassLoader().getResource("log4j2.xml");
		Assert.assertNotNull(resource);

		String content = Files.readString(Path.of(resource.toURI()), StandardCharsets.UTF_8);
		Assert.assertTrue(content.contains("TimeBasedTriggeringPolicy"));
		Assert.assertTrue(content.contains("twopair-miniredis-%d{yyyy-MM-dd}-%i.log.gz"));
		Assert.assertTrue(content.contains("IfLastModified age=\"30d\""));
		Assert.assertTrue(content.contains("miniredis.log.level:-debug"));
		Assert.assertTrue(content.contains("miniredis.log.dir:-logs"));
	}
}
