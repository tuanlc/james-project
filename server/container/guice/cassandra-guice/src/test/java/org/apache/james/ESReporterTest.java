/****************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one   *
 * or more contributor license agreements.  See the NOTICE file *
 * distributed with this work for additional information        *
 * regarding copyright ownership.  The ASF licenses this file   *
 * to you under the Apache License, Version 2.0 (the            *
 * "License"); you may not use this file except in compliance   *
 * with the License.  You may obtain a copy of the License at   *
 *                                                              *
 *   http://www.apache.org/licenses/LICENSE-2.0                 *
 *                                                              *
 * Unless required by applicable law or agreed to in writing,   *
 * software distributed under the License is distributed on an  *
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY       *
 * KIND, either express or implied.  See the License for the    *
 * specific language governing permissions and limitations      *
 * under the License.                                           *
 ****************************************************************/
package org.apache.james;

import static com.jayway.awaitility.Awaitility.await;
import static com.jayway.restassured.RestAssured.given;
import static com.jayway.restassured.config.EncoderConfig.encoderConfig;
import static com.jayway.restassured.config.RestAssuredConfig.newConfig;

import java.io.IOException;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.Timer;
import java.util.TimerTask;
import java.util.stream.Collectors;

import org.apache.commons.net.imap.IMAPClient;
import org.apache.http.client.utils.URIBuilder;
import org.apache.james.jmap.HttpJmapAuthentication;
import org.apache.james.jmap.api.access.AccessToken;
import org.apache.james.modules.TestESMetricReporterModule;
import org.apache.james.utils.GuiceServerProbe;
import org.elasticsearch.client.Client;
import org.elasticsearch.index.query.QueryBuilders;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import com.google.common.base.Charsets;
import com.google.common.base.Throwables;
import com.jayway.awaitility.Duration;
import com.jayway.restassured.RestAssured;
import com.jayway.restassured.builder.RequestSpecBuilder;
import com.jayway.restassured.http.ContentType;

public class ESReporterTest {

    private static final int IMAP_PORT = 1143;
    private static final int DELAY_IN_MS = 100;
    private static final int PERIOD_IN_MS = 100;

    private static final String DOMAIN = "james.org";
    private static final String USERNAME = "user1@" + DOMAIN;
    private static final String PASSWORD = "secret";

    private EmbeddedElasticSearchRule embeddedElasticSearchRule = new EmbeddedElasticSearchRule();

    private Timer timer;

    @Rule
    public CassandraJmapTestRule cassandraJmap = new CassandraJmapTestRule(embeddedElasticSearchRule, new EmbeddedCassandraRule());

    private JmapJamesServer server;
    private AccessToken accessToken;

    @Before
    public void setup() throws Exception {
        server = cassandraJmap.jmapServer();
        server.start();
        GuiceServerProbe serverProbe = server.getGuiceProbeProvider().getProbe(GuiceServerProbe.class);
        serverProbe.addDomain(DOMAIN);
        serverProbe.addUser(USERNAME, PASSWORD);

        RestAssured.requestSpecification = new RequestSpecBuilder()
                .setContentType(ContentType.JSON)
                .setAccept(ContentType.JSON)
                .setConfig(newConfig().encoderConfig(encoderConfig().defaultContentCharset(Charsets.UTF_8)))
                .setPort(server.getJmapProbe().getJmapPort())
                .build();
        accessToken = HttpJmapAuthentication.authenticateJamesUser(baseUri(), USERNAME, PASSWORD);

        timer = new Timer();
    }

    private URIBuilder baseUri() {
        return new URIBuilder()
            .setScheme("http")
            .setHost("localhost")
            .setPort(server.getJmapProbe().getJmapPort())
            .setCharset(Charsets.UTF_8);
    }

    @After
    public void tearDown() throws Exception {
        if (server != null) {
            server.stop();
        }

        timer.cancel();
    }

    @Test
    public void timeMetricsShouldBeReportedWhenImapCommandsReceived() throws Exception {
        IMAPClient client = new IMAPClient();
        client.connect(InetAddress.getLocalHost(), IMAP_PORT);
        client.login(USERNAME, PASSWORD);
        
        TimerTask timerTask = new TimerTask() {
            @Override
            public void run() {
                try {
                    client.list("", "*");
                } catch (IOException e) {
                    throw Throwables.propagate(e);
                }
            }
        };
        timer.schedule(timerTask, DELAY_IN_MS, PERIOD_IN_MS);

        await().atMost(Duration.TEN_MINUTES)
            .until(() -> checkMetricRecordedInElasticSearch());
    }

    @Test
    public void timeMetricsShouldBeReportedWhenJmapRequestsReceived() throws Exception {
        TimerTask timerTask = new TimerTask() {
            @Override
            public void run() {
                    given()
                        .header("Authorization", accessToken.serialize())
                        .body("[[\"getMailboxes\", {}, \"#0\"]]")
                    .when()
                        .post("/jmap")
                    .then()
                        .statusCode(200);
            }
        };
        timer.schedule(timerTask, DELAY_IN_MS, PERIOD_IN_MS);

        await().atMost(Duration.TEN_MINUTES)
            .until(() -> checkMetricRecordedInElasticSearch());
    }

    private boolean checkMetricRecordedInElasticSearch() {
        try (Client client = embeddedElasticSearchRule.getNode().client()) {
            return !Arrays.stream(client.prepareSearch()
                    .setQuery(QueryBuilders.matchAllQuery())
                    .get().getHits().getHits())
                .filter(searchHit -> searchHit.getIndex().startsWith(TestESMetricReporterModule.METRICS_INDEX))
                .collect(Collectors.toList())
                .isEmpty();
        } catch (Exception e) {
            return false;
        }
    }
}
