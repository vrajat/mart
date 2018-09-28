package io.inviscid.mart.server;

import com.codahale.metrics.Counter;
import com.codahale.metrics.MetricRegistry;
import io.inviscid.metricsink.redshift.MySqlSink;
import io.inviscid.metricsink.redshift.RedshiftDb;
import io.inviscid.metricsink.redshift.UserQuery;
import io.inviscid.qan.RedshiftClassifier;
import io.inviscid.qan.enums.QueryType;
import io.inviscid.qan.enums.RedshiftEnum;

import org.apache.calcite.sql.parser.SqlParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.List;

public class BadQueriesCron extends Cron {
  private static Logger logger = LoggerFactory.getLogger(BadQueriesCron.class);

  RedshiftClassifier redshiftClassier;
  Counter numQueriesProcessed;
  Counter numBadQueries;
  Counter parseExceptions;

  BadQueriesCron(int frequency, MetricRegistry metricRegistry,
                 RedshiftDb redshiftDb, MySqlSink mySqlSink) {
    super(mySqlSink, redshiftDb, frequency, metricRegistry, "badQueriesCron");

    redshiftClassier = new RedshiftClassifier();
    numQueriesProcessed = metricRegistry.counter("inviscid.bad_queries_cron.num_queries_processed");
    numBadQueries = metricRegistry.counter("inviscid.bad_queries_cron.num_bad_queries");
    parseExceptions = metricRegistry.counter("inviscid.bad_queries_cron.num_parse_exception");
  }

  /**
   * Run one iteration to get QueryStats from Redshift and
   * store into MySQL.
   */
  @Override
  public void run() {
    logger.debug("Run one instance of BadQueriesCron");

    LocalDateTime endRange = LocalDateTime.now();

    try {
      iterations.inc();
      List<UserQuery> userQueryList = redshiftDb.getQueries(startRange, endRange);

      numQueriesProcessed.inc(userQueryList.size());
      logger.info("Processing " + userQueryList.size() + "queries" );

      long prevFound = numBadQueries.getCount();
      for (UserQuery userQuery : userQueryList) {
        try {
          List<QueryType> queryTypes = redshiftClassier.classify(userQuery.query);
          if (queryTypes.contains(RedshiftEnum.BAD_TOOMANYJOINS)) {
            numBadQueries.inc();
            mySqlSink.insertBadQueries(userQuery);
          }
        } catch (SqlParseException parseExc) {
          parseExceptions.getCount();
          logger.warn("Parse failed: ", parseExc);
        }
      }

      logger.info("Bad queries found: " + (numBadQueries.getCount() - prevFound));
    } catch (Exception exc) {
      failedIterations.inc();
      logger.warn("Exception thrown", exc);
    } finally {
      startRange = endRange;
    }
  }
}
