package main.dke.detectURLtopo;

import java.util.*;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.storm.Config;
import org.apache.storm.StormSubmitter;
import org.apache.storm.generated.AlreadyAliveException;
import org.apache.storm.generated.AuthorizationException;
import org.apache.storm.generated.InvalidTopologyException;
import org.apache.storm.generated.KillOptions;
import org.apache.storm.generated.Nimbus.Client;
import org.apache.storm.generated.NotAliveException;
import org.apache.storm.jdbc.bolt.JdbcInsertBolt;
import org.apache.storm.jdbc.common.Column;
import org.apache.storm.jdbc.common.ConnectionProvider;
import org.apache.storm.jdbc.common.HikariCPConnectionProvider;
import org.apache.storm.jdbc.mapper.JdbcMapper;
import org.apache.storm.jdbc.mapper.SimpleJdbcMapper;
import org.apache.storm.kafka.BrokerHosts;
//import org.apache.storm.kafka.spout.KafkaSpout;
import org.apache.storm.kafka.KafkaSpout;
import org.apache.storm.kafka.SpoutConfig;
import org.apache.storm.kafka.StringScheme;

import org.apache.storm.kafka.ZkHosts;
import org.apache.storm.kafka.bolt.KafkaBolt;
import org.apache.storm.kafka.bolt.MyKafkaBolt;
import org.apache.storm.kafka.bolt.mapper.FieldNameBasedTupleToKafkaMapper;
import org.apache.storm.kafka.bolt.selector.DefaultTopicSelector;
import org.apache.storm.spout.SchemeAsMultiScheme;
import org.apache.storm.thrift.TException;
import org.apache.storm.topology.SpoutDeclarer;
import org.apache.storm.topology.TopologyBuilder;
import org.apache.storm.tuple.ITuple;
import org.apache.storm.utils.NimbusClient;
import org.apache.storm.utils.Utils;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;


public class MainTopo {
    private static Log LOG = LogFactory.getLog(MainTopo.class);

    @Option(name = "--help", aliases = {"-h"}, usage = "print help message")
    private boolean _help = false;

    @Option(name = "--topologyName", aliases = {"--name"}, metaVar = "TOPOLOGIE NAME", usage = "name of topology")
    private static String topologyName = "Topo";

    @Option(name = "--inputTopic", aliases = {"--input"}, metaVar = "INPUT TOPIC", usage = "name of input kafka topic")
    private static String inputTopic = "input";

    @Option(name = "--outputTopic", aliases = {"--output"}, metaVar = "OUTPUT TOPIC", usage = "name of output kafka topic")
    private static String outputTopic = "output";

    @Option(name = "--testTime", aliases = {"--t"}, metaVar = "TIME", usage = "how long should run topology")
    private static int testTime = 3;

    @Option(name = "--numWorkers", aliases = {"--workers"}, metaVar = "WORKERS", usage = "number of workers")
    private static int numWorkers = 8;

    @Option(name = "--zookeeperHosts", aliases = {"--zookeeper"}, metaVar = "ZOOKEEPER HOST", usage = "path of zookeeper host")
    private static String zkhosts = "MN:42181,SN01:42181,SN02:42181,SN03:42181,SN04:42181,SN05:42181,SN06:42181,SN07:42181,SN08:42181";

    @Option(name = "--brokerList", aliases = {"--broker"}, metaVar = "BROKER LIST", usage = "path of broker list, bootstrap servers")
    private static String bootstrap = "MN:9092,SN01:9092,SN02:9092,SN03:9092,SN04:9092,SN05:9092,SN06:9092,SN07:9092,SN08:9092";

    @Option(name = "--parallelismHint", aliases = {"--parm"}, metaVar = "PARALLELISM HINT", usage = "number of spout, bolts(KafkaSpout-ExtractBolt-ExpandBolt-ValidateBolt-DetectBolt-KafkaBolt")
    private static String paralleism = "1 2 4 2 2 1";

    public static void main(String[] args) throws NotAliveException, InterruptedException, TException {
        new MainTopo().topoMain(args);
    }

    public void topoMain(String[] args) throws InterruptedException, NotAliveException, TException {
        CmdLineParser parser = new CmdLineParser(this);
        parser.setUsageWidth(150);

        try {
            // parse the arguments.
            parser.parseArgument(args);
        } catch (CmdLineException e) {
            // if there's a problem in the command line,
            // you'll get this exception. this will report
            // an error message.
            System.err.println(e.getMessage());
            _help = true;
        }
        if (_help) {
            parser.printUsage(System.err);
            System.err.println();
            return;
        }
        if (numWorkers <= 0) {
            throw new IllegalArgumentException("Need at least one worker");
        }
        if (topologyName == null || topologyName.isEmpty()) {
            throw new IllegalArgumentException("Topology Name must be something");
        }

			/* Kafka Spout Configuration */
        BrokerHosts brokerHosts = new ZkHosts(zkhosts);

        SpoutConfig kafkaSpoutConfig = new SpoutConfig(brokerHosts, inputTopic, "/" + inputTopic,
                UUID.randomUUID().toString());
        kafkaSpoutConfig.scheme = new SchemeAsMultiScheme(new StringScheme());
	
			/* KafkaBolt Configuration */
        Properties props = new Properties();
        props.put("metadata.broker.list", bootstrap);
        props.put("bootstrap.servers", bootstrap);
        props.put("acks", "1");
        props.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        props.put("value.serializer", "org.springframework.kafka.support.serializer.JsonSerializer");

        KafkaSpout kafkaSpout = new KafkaSpout(kafkaSpoutConfig);
        ExtractURLBolt extractBolt = new ExtractURLBolt();
        ExpandURLBolt expandBolt = new ExpandURLBolt();
        ValidateURLBolt validateBolt = new ValidateURLBolt();
        DetectBolt detectBolt = new DetectBolt();

			/* KafkaBolt */
        MyKafkaBolt kafkabolt = new MyKafkaBolt().withProducerProperties(props)
                .withTopicSelector(new DefaultTopicSelector(outputTopic))
                .withTupleToKafkaMapper(new FieldNameBasedTupleToKafkaMapper());

			/* Topology Build */
        TopologyBuilder builder = new TopologyBuilder();

        ArrayList<Integer> parameters = new ArrayList<Integer>();
        String[] params = paralleism.split(" ");

        for(String p : params) {
            parameters.add(Integer.parseInt(p));
        }

        builder.setSpout("kafka-spout", kafkaSpout, parameters.get(0));
        builder.setBolt("extract-bolt", extractBolt, parameters.get(1)).shuffleGrouping("kafka-spout");
        builder.setBolt("expand-bolt", expandBolt, parameters.get(2)).shuffleGrouping("extract-bolt");
		builder.setBolt("validate-bolt", validateBolt, parameters.get(3)).shuffleGrouping("expand-bolt");
		builder.setBolt("detect-bolt", detectBolt, parameters.get(4)).shuffleGrouping("validate-bolt");
        builder.setBolt("kafka-bolt", kafkabolt, parameters.get(5)).shuffleGrouping("detect-bolt");            // Store Data to Kafka

        Config config = new Config();
        config.setNumWorkers(numWorkers);

        StormSubmitter.submitTopology(topologyName, config, builder.createTopology());
        printSystem();

        try {
            Thread.sleep(testTime * 60 * 1000);

            Map<String, Object> conf = Utils.readStormConfig();
            Client client = NimbusClient.getConfiguredClient(conf).getClient();
            KillOptions killOpts = new KillOptions();
            killOpts.set_wait_secs(0);
            client.killTopologyWithOpts(topologyName, killOpts);
        } catch (AlreadyAliveException ae) {
            LOG.info(ae.get_msg());
        } catch (InvalidTopologyException ie) {
            LOG.info(ie.get_msg());
        }
    }

    public static void printSystem() {
        System.out.println("");
        System.out.println("");
        System.out.println("   #     #     #     #        ###   #####   ###  #######  #     #   #####	#     #  ######   #");
        System.out.println("   ##   ##    # #    #         #   #     #   #   #     #  #     #  #     	#     #  #     #  #");
        System.out.println("   #  #  #  #     #  #         #   #         #   #     #  #     #   #####	#     #  ######   #");
        System.out.println("   #     #  #######  #         #   #         #   #     #  #     #       #	#     #  #   #    #");
        System.out.println("   #     #  #     #  #######  ###   #####   ###  #######   #####    #####	 #####   #     #  #######");
        System.out.println("");
        System.out.println("");
        System.out.println("");
        System.out.println("");
        System.out.println("   ######  ###### #######  ######   #####   #######  ###  #######  #     #     #####  #   #  #####  #######  ######  #     #");
        System.out.println("   #     # #         #     #        #          #      #   #     #  # #   #    #        # #   #         #     #       ##   ##");
        System.out.println("   #     # #####     #     #####    #          #      #   #     #  #  #  #     #####    #    #####     #     #####   #  #  #");
        System.out.println("   #     # #         #     #        #          #      #   #     #  #   # #          #   #         #    #     #       #     #");
        System.out.println("   ######  ######    #     ######   #####      #     ###  #######  #     #     #####    #    #####     #     ######  #     #");
        System.out.println("");
        System.out.println("");
        System.out.println("");
        System.out.println("");
    }
}