package com.alibaba.otter.canal.process;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutionException;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import com.alibaba.otter.canal.client.CanalConnector;
import com.alibaba.otter.canal.client.CanalConnectors;
import com.alibaba.otter.canal.protocol.Message;
import com.alibaba.otter.canal.protocol.CanalEntry.Column;
import com.alibaba.otter.canal.protocol.CanalEntry.Entry;
import com.alibaba.otter.canal.protocol.CanalEntry.EntryType;
import com.alibaba.otter.canal.protocol.CanalEntry.EventType;
import com.alibaba.otter.canal.protocol.CanalEntry.RowChange;
import com.alibaba.otter.canal.protocol.CanalEntry.RowData;
import com.alibaba.otter.canal.common.GetProperties;
import com.alibaba.fastjson.JSON;

/**
 * kafka Producer
 * 
 * @author sasou <admin@php-gene.com> web:http://www.php-gene.com/
 * @version 1.0.0
 */
public class Kafka implements Runnable {
	private KafkaProducer<Integer, String> producer;
	private CanalConnector connector = null;
	private int system_debug = 0;
	private String thread_name = null;
	private String canal_destination = null;

	public Kafka(String name) {
		thread_name = "canal[" + name + "]:";
		canal_destination = name;
	}

	public void process() {
		system_debug = GetProperties.system_debug;
		Properties props = new Properties();
		props.put("bootstrap.servers", GetProperties.target_ip + ":" + GetProperties.target_port);
		props.put("client.id", thread_name + "_Producer");
		props.put("key.serializer", "org.apache.kafka.common.serialization.IntegerSerializer");
		props.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");

		int batchSize = 1000;
		connector = CanalConnectors.newSingleConnector(
				new InetSocketAddress(GetProperties.canal_ip, GetProperties.canal_port), canal_destination,
				GetProperties.canal_username, GetProperties.canal_password);

		connector.connect();
		if (!"".equals(GetProperties.canal_filter)) {
			connector.subscribe(GetProperties.canal_filter);
		} else {
			connector.subscribe();
		}

		connector.rollback();

		try {
			producer = new KafkaProducer<>(props);
			while (true) {
				Message message = connector.getWithoutAck(batchSize); // get batch num
				long batchId = message.getId();
				int size = message.getEntries().size();
				if (!(batchId == -1 || size == 0)) {;
					if (syncEntry(message.getEntries())) {
						connector.ack(batchId); // commit
					} else {
						connector.rollback(batchId); // rollback
						System.out.println(thread_name + "parser of eromanga-event has an error");
					}
				}
			}
		} finally {
			if (connector != null) {
				connector.disconnect();
				connector = null;
			}
			if (producer != null) {
				producer.close();
				producer = null;
			}
		}
	}

	public void run() {
		while (true) {
			try {
				process();
			} catch (Exception e) {
				System.out.println(thread_name + "canal link failure!");
			}
		}
	}

	private boolean syncEntry(List<Entry> entrys) {
		String topic = "";
		int no = 0;
		RecordMetadata metadata = null;
		boolean ret = true;
		for (Entry entry : entrys) {
			if (entry.getEntryType() == EntryType.TRANSACTIONBEGIN
					|| entry.getEntryType() == EntryType.TRANSACTIONEND) {
				continue;
			}

			RowChange rowChage = null;
			try {
				rowChage = RowChange.parseFrom(entry.getStoreValue());
			} catch (Exception e) {
				throw new RuntimeException(
						thread_name + "parser of eromanga-event has an error , data:" + entry.toString(), e);
			}

			EventType eventType = rowChage.getEventType();
			Map<String, Object> data = new HashMap<String, Object>();
			Map<String, Object> head = new HashMap<String, Object>();
			head.put("binlog_file", entry.getHeader().getLogfileName());
			head.put("binlog_pos", entry.getHeader().getLogfileOffset());
			head.put("db", entry.getHeader().getSchemaName());
			head.put("table", entry.getHeader().getTableName());
			head.put("type", eventType);
			data.put("head", head);
			topic = "sync_" + entry.getHeader().getSchemaName() + "_" + entry.getHeader().getTableName();
			no = (int) entry.getHeader().getLogfileOffset();
			for (RowData rowData : rowChage.getRowDatasList()) {
				if (eventType == EventType.DELETE) {
					data.put("before", makeColumn(rowData.getBeforeColumnsList()));
				} else if (eventType == EventType.INSERT) {
					data.put("after", makeColumn(rowData.getAfterColumnsList()));
				} else {
					data.put("before", makeColumn(rowData.getBeforeColumnsList()));
					data.put("after", makeColumn(rowData.getAfterColumnsList()));
				}
				String text = JSON.toJSONString(data);
				try {
					metadata = producer.send(new ProducerRecord<>(topic, no, text)).get();
					if (metadata == null) {
						ret = false;
					}
					if (system_debug > 0) {
						System.out.println(thread_name + "data(" + topic + "," + no + ", " + text + ")");
					}
				} catch (InterruptedException | ExecutionException e) {
					if (system_debug > 0) {
						System.out.println(thread_name + "kafka sent message failure!");
					}
					ret = false;
				}
			}
			data.clear();
			data = null;
		}
		return ret;
	}

	private List<Map<String, Object>> makeColumn(List<Column> columns) {
		List<Map<String, Object>> list = new ArrayList<Map<String, Object>>();
		for (Column column : columns) {
			Map<String, Object> one = new HashMap<String, Object>();
			one.put("name", column.getName());
			one.put("value", column.getValue());
			one.put("update", column.getUpdated());
			list.add(one);
		}
		return list;
	}

}