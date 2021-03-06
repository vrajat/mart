package io.dblint.mart.server;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import io.dblint.mart.metricsink.redshift.MySqlSink;
import io.dblint.mart.metricsink.redshift.RedshiftDb;
import io.dblint.mart.server.commands.MySqlCommands;
import io.dblint.mart.server.configuration.JdbcConfiguration;
import io.dblint.mart.server.pojo.GitState;
import io.dblint.mart.server.resources.DbLintResource;
import io.dblint.mart.server.resources.RedshiftResource;
import io.dblint.mart.server.resources.RootResource;
import io.dblint.mart.sqlplanner.planner.Parser;
import io.dropwizard.Application;
import io.dropwizard.jackson.Jackson;
import io.dropwizard.lifecycle.setup.ExecutorServiceBuilder;
import io.dropwizard.lifecycle.setup.ScheduledExecutorServiceBuilder;
import io.dropwizard.setup.Bootstrap;
import io.dropwizard.setup.Environment;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class MartApplication extends Application<MartConfiguration> {

  public static void main(final String[] args) throws Exception {
    new MartApplication().run(args);
  }

  @Override
  public String getName() {
    return "mart";
  }

  @Override
  public void initialize(final Bootstrap<MartConfiguration> bootstrap) {
    bootstrap.setObjectMapper(Jackson.newMinimalObjectMapper().registerModule(new Jdk8Module()));
    bootstrap.addCommand(new MySqlCommands());
  }

  @Override
  public void run(final MartConfiguration configuration,
                  final Environment environment)
      throws IOException {

    JdbcConfiguration redShift = configuration.redshift;
    JdbcConfiguration mySql = configuration.mySql;

    if (redShift != null && mySql != null) {
      RedshiftDb redshiftDb = new RedshiftDb(redShift.getUrl(), redShift.getUser(),
          redShift.getPassword(), environment.metrics());
      MySqlSink mySqlSink = new MySqlSink(mySql.getUrl(), mySql.getUser(),
          mySql.getPassword(), environment.metrics());
      mySqlSink.initialize();

      ScheduledExecutorServiceBuilder serviceBuilder = environment.lifecycle()
          .scheduledExecutorService("mart_application");
      ScheduledExecutorService scheduledExecutorService = serviceBuilder.build();

      ExecutorServiceBuilder executorServiceBuilder = environment.lifecycle()
          .executorService("mart_resource");
      ExecutorService executorService = executorServiceBuilder.build();

      if (configuration.queryStatsCron != null) {
        QueryStatsCron cron = new QueryStatsCron(configuration.queryStatsCron.frequencyMin,
            environment.metrics(), redshiftDb, mySqlSink);

        scheduledExecutorService.scheduleAtFixedRate(cron,
            configuration.queryStatsCron.delayMin, configuration.queryStatsCron.frequencyMin,
            TimeUnit.MINUTES);
        environment.healthChecks().register("QueryStatsCron", new CronHealthCheck(cron));
      }

      if (configuration.badQueriesCron != null) {
        BadQueriesCron cron = new BadQueriesCron(configuration.badQueriesCron.frequencyMin,
            environment.metrics(), redshiftDb, mySqlSink);

        scheduledExecutorService.scheduleAtFixedRate(cron,
            configuration.badQueriesCron.delayMin, configuration.badQueriesCron.frequencyMin,
            TimeUnit.MINUTES);
        environment.healthChecks().register("BadQueriesCron", new CronHealthCheck(cron));
      }

      if (configuration.connectionsCron != null) {
        ConnectionsCron cron = new ConnectionsCron(mySqlSink, redshiftDb,
            configuration.connectionsCron.frequencyMin, environment.metrics());

        scheduledExecutorService.scheduleAtFixedRate(cron,
            configuration.connectionsCron.delayMin, configuration.connectionsCron.frequencyMin,
            TimeUnit.MINUTES);
        environment.healthChecks().register("ConnectionsCron", new CronHealthCheck(cron));
      }

      {
        ConnectionsCron cron = new ConnectionsCron(mySqlSink, redshiftDb, 0, environment.metrics());
        RedshiftResource resource = new RedshiftResource(cron, executorService);
        environment.jersey().register(resource);
        environment.healthChecks().register("Redshift Resource High CPU",
            new CronHealthCheck(cron));
      }
    }

    {
      InputStream stream =  getClass().getClassLoader().getResourceAsStream("git.properties");
      GitState gitState = new ObjectMapper().readValue(stream, GitState.class);

      RootResource rootResource = new RootResource();
      DbLintResource resource = new DbLintResource(new Parser(), gitState);
      environment.jersey().register(resource);
      environment.jersey().register(rootResource);
      environment.jersey().register(new SqlParseExceptionMapper());
    }
  }
}
