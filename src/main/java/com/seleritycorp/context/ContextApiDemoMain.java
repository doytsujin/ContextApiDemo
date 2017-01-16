/*
 * Copyright (C) 2017 Selerity, Inc. (support@seleritycorp.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.seleritycorp.context;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;

/**
 * Main entry point for demo of the Selerity Context API.
 */
public class ContextApiDemoMain {
  /**
   * Back-off period in seconds before follow-up UPDATE queries.
   */
  private static final int PAUSE_SECS = 30;

  /**
   * Upper bound for how many entities a DDS query should return.
   */
  private static final int MAX_ENTITIES = 20;
  
  /**
   * Email address to which send questions. 
   */
  private static final String SUPPORT_EMAIL_ADDRESS = "support@selerityinc.com";

  @Option(name = "-help", usage = "Prints this help page", aliases = {"--help","-h","-?"})
  boolean showHelp = false;
  
  @Option(name = "-apiserver", metaVar = "URL", usage = "The Context API server to connect to")
  String apiServerRootUrl = "context-api-test.seleritycorp.com";

  @Option(name = "-apikey", metaVar = "API_KEY", usage = "The key used for the API connections")
  String apiKey = null;

  @Option(name = "-sessionid", metaVar = "SESSION_ID", usage = "The session id to use for requests")
  String sessionId = "<automatic>";

  @Option(name = "-sources", usage = "Output the entitled sources. In this mode, no query for "
      + "content is made.")
  boolean requestedSourcesQuery = false;
  
  @Option(name = "-query", metaVar = "QUERY", usage = "Query only for content items of the given "
      + "entity (E.g.: AAPL, Google)")
  String query = "";
  
  @Option(name = "-exact", usage = "When matching entities, consider only exact matches, instead of"
      + "also partial matches")
  boolean exactMatching = false;

  @Option(name = "-querytype", metaVar = "TYPE", usage = "The type of query to make. One of\n"
      + "  - FEED            <- use to keep on top of latest\n"
      + "                       breaking news\n"
      + "  - RECOMMENDATION  <- use to get 'up to speed'\n"
      + "                       quickly\n"
      + "  - SURVEY\n"
      + "  - SEARCH\n"
      + "  - DISCOVERY\n"
      + "(No AUTHOR query, as this demo does not allow to specify authors)")
  String queryType = "FEED";

  private JsonUtils jsonUtils = new JsonUtils();
  private QueryUtils queryUtils;

  /**
   * Handles argument parsing.
   *
   * @param args arguments to use as command line arguments.
   */
  @SuppressFBWarnings(value = "DM_EXIT",
      justification = "Hard exiting on invalid arguments is wanted and desired")
  private void parseArgs(final String[] args) {    
    CmdLineParser parser = new CmdLineParser(this);
    try {
      parser.parseArgument(args);
    } catch (CmdLineException e) {
      // Parsing the command line arguments failed. So we print a help screen
      System.err.println(e.getMessage());
      System.err.println();
      parser.printUsage(System.err);
      System.exit(1);
    }
    
    if (showHelp) {
      System.out.println("The following options are available:");
      parser.printUsage(System.out);
      System.exit(1);
    }
    
    // Making sure we to avoid obviously wrong api keys.
    if (apiKey == null || apiKey.isEmpty()) {
      System.err.println("No usable api key given. Please run the demo command with\n"
          + "\n"
          + "  -apikey INSERT-YOUR-API-KEY-HERE\n"
          + "\n"
          + "If you have not yet gotten an API key, get in touch with us at "
          + SUPPORT_EMAIL_ADDRESS);
      System.exit(1);
    }

    // Fixing up apiServerRootUrl to be a proper URL.
    if (!apiServerRootUrl.contains("://")) {
      apiServerRootUrl = "https://" + apiServerRootUrl;
    }
    
    // Making sure, we're on a good session
    if (sessionId == null || sessionId.isEmpty() || "<automatic>".equals(sessionId)) {
      sessionId = UUID.randomUUID().toString();
    }

    // Fixing up queryType
    switch (queryType) {
      case "FEED":
      case "RECOMMENDATION":
      case "SURVEY":
      case "SEARCH":
      case "DISCOVERY":
        break;
      default:
        System.err.println("Unknown query type " + queryType + ". Switching to FEED.");
        queryType = "FEED";
    }
  }

  /**
   * Prints a recommended content item to stdout.
   * 
   * @param recommendation The recommended content item
   */
  private void printRecommendation(JsonObject recommendation) {
    System.out.println("");
    System.out.println("* " + jsonUtils.getAsString(recommendation, "headline"));
    System.out.println("");
    System.out.println("  contentID: " + jsonUtils.getAsString(recommendation, "contentID"));
    System.out.println("  contentType: " + jsonUtils.getAsString(recommendation, "contentType"));
    System.out.println("  source: " + jsonUtils.getAsString(recommendation, "source"));
    System.out.println("  timestamp: " + jsonUtils.getAsString(recommendation, "timestamp"));
    System.out.println("  score: " + jsonUtils.getAsString(recommendation, "score"));
    System.out.println("  summary: " + jsonUtils.getAsString(recommendation, "summary"));
    System.out.println("  socialInfo->author: " + jsonUtils.getAsString(recommendation,
        "socialInfo", "author"));
    System.out.println("  linkURL: " + jsonUtils.getAsString(recommendation, "linkURL"));

    JsonArray relatedContentArray = recommendation.get("relatedContent").getAsJsonArray();
    for (JsonElement relatedContentElement: relatedContentArray) {
      JsonObject relatedContentObject = relatedContentElement.getAsJsonObject();
      String line = "  related content:";
      line += " " + jsonUtils.getAsString(relatedContentObject, "relationship");
      line += " " + jsonUtils.getAsString(relatedContentObject, "contentItem", "contentType");
      line += " " + jsonUtils.getAsString(relatedContentObject, "contentItem", "linkURL");
      
      System.out.println(line);
    }
  }

  /**
   * Queries for and prints entitled sources.
   *
   * @throws Exception, if any error occurs.
   */
  private void printEntitledSources() throws Exception {
    JsonArray sources = queryUtils.queryEntitledSources();

    if (sources.size() == 0) {
      System.out.println("API key is not entitled for any source.");
    } else {    
      System.out.println("API key is entitled for the following sources:");
      for (JsonElement source : sources) {
        System.out.println("* " + source.getAsString());
      }
    }
  }

  /**
   * Resolves the query to entities, dumps information about them, and yields the entity ids. 
   * 
   * <p/>Queries DDS for entities that match the query command line argument. If the command line
   * arguments requested exact matching, exact matching is requested from DDS, otherwise partial
   * matching is performed.
   * 
   * </p>Only up to {@link #MAX_ENTITIES} are fetched from DDS. 
   *
   * @return the entity ids of the entities for the query
   * @throws Exception, if any error occurs
   */
  private Iterable<String> resolveQueryEntityIds() throws Exception {
    List<String> entityIds = new LinkedList<>();
    
    JsonArray results = queryUtils.queryEntities(query, exactMatching, MAX_ENTITIES);
    
    System.out.println("Query for '" + query + "' will look for those entities:");
    for (JsonElement resultElement : results) {
      JsonObject result = resultElement.getAsJsonObject();
      String id = result.getAsJsonPrimitive("entityID").getAsString();
      String type = result.getAsJsonPrimitive("entityType").getAsString();
      String name = result.getAsJsonPrimitive("displayName").getAsString();
      String description = result.getAsJsonPrimitive("description").getAsString();
      
      System.out.println("* " + id + " -> " + name + " (" + type + ", " + description + ")");
      
      entityIds.add(id);
    }
    
    return entityIds;
  }

  /**
   * Waits a bit before asking API for updates.
   * 
   * @throws InterrutedException upon Thread interruption.
   */
  private void pauseBeforeUpdate() throws InterruptedException {
    try {
      System.out.println("Sleeping for " + PAUSE_SECS + " seconds before asking for updated "
          + "content items");
      Thread.sleep(PAUSE_SECS * 1000);
    } catch (InterruptedException e) {
      throw e;
    }    
  }

  /**
   * Performs intitial query, performs update endlessly, and prints new content items.
   * 
   * @throws Exception, if any errors occur
   */
  private void queryWithUpdates() throws Exception { 
    // First, we resolve the query to entity ids.
    Iterable<String> entityIds = resolveQueryEntityIds();
    
    int batchSize = 10; // requesting only up to 10 items per query
    Deque<String> seenContentIds = new LinkedList<>(); // Used to filter seen items from updates
    JsonArray recommendations; // Will hold the recommended content items of the last query
    boolean isInitial = true; // Whether or not to perform an INITIAL or UPDATE query.
    while (true) {
      // Perform the query
      recommendations = queryUtils.queryRecommendations(queryType, isInitial, batchSize, entityIds);
      isInitial = false; // From now on, all queries are UPDATES

      // Dumping the response
      System.out.println("Received " + recommendations.size() + " recommendations. "
          + "(Printing only new ones.)");
      for (JsonElement recommendationElement : recommendations) {
        JsonObject recommendation = recommendationElement.getAsJsonObject();
        String contentId = recommendation.getAsJsonPrimitive("contentID").getAsString();
        if (contentId != null) {
          if (!seenContentIds.contains(contentId)) {
            // Content item has not been seen, so we print it.
            printRecommendation(recommendation);
            
            // And we remember that we saw that content item.
            seenContentIds.addFirst(contentId);
            
            // To avoid keeping too many items in memory, we prune old items. 
            if (seenContentIds.size() > 2 * batchSize) {
              seenContentIds.removeLast();                
            }
          }
        }
      }

      // Backing-off a bit before the next query to avoid hammering servers.
      pauseBeforeUpdate();
    }   
  }
 
  /**
   * Runs Context API demo.
   *
   * @param args arguments to use as command line arguments.
   */
  public void doMain(final String[] args) {
    parseArgs(args);

    System.out.println("Using Selerity Context API server at " + apiServerRootUrl);

    // Setting up the endpoint config
    RequestUtils requestUtils = new RequestUtils(apiServerRootUrl);

    // Setting up query helpers for the endpoint
    queryUtils = new QueryUtils(apiKey, sessionId, requestUtils);

    // Now that setup is complete, start the queries. 
    try {
      if (requestedSourcesQuery) {
        printEntitledSources();
      } else {
        queryWithUpdates();
      }
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  /**
   * Spawns a demo object and runs it.
   * 
   * @param args arguments to use as command line arguments.
   */
  public static void main(final String[] args) {
    (new ContextApiDemoMain()).doMain(args);
  }
}
