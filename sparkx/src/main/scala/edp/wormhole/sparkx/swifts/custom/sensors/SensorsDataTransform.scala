package edp.wormhole.sparkx.swifts.custom.sensors

import java.io.Serializable
import java.util

import com.alibaba.fastjson.{JSON, JSONObject}
import edp.wormhole.sparkx.spark.log.EdpLogging

import scala.collection.JavaConversions._
import edp.wormhole.sparkx.swifts.custom.sensors.ase.AESUtil
import edp.wormhole.sparkx.swifts.custom.sensors.entry.{EventEntry, PropertyColumnEntry}
import edp.wormhole.sparkxinterface.swifts.{SwiftsProcessConfig, WormholeConfig}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.catalyst.expressions.GenericRowWithSchema
import org.apache.spark.sql.types._
import org.apache.spark.sql.{DataFrame, Dataset, Encoders, Row, SparkSession}
import org.joda.time.DateTime

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
/**
  * Created by IntelliJ IDEA.
  *
  * @author daemon
  *  19/11/14 15:35
  *       To change this template use File | Settings | File Templates.
  */
class SensorsDataTransform extends EdpLogging{


  def  transform(session: SparkSession, df: DataFrame, flowConfig: SwiftsProcessConfig,param:String,streamConfig: WormholeConfig):DataFrame={
    import session.sqlContext.implicits._
    if(param==null){
      throw new IllegalArgumentException("param must be not empty");
    }
    val originalSourceNamespace = if(session.sessionState.conf.contains("original_source_namespace")) {
      session.sessionState.conf.getConfString("original_source_namespace")
    } else {
      ""
    }
    val paramUtil=new ParamUtils(param,streamConfig.zookeeper_address,streamConfig.zookeeper_path+"/sensors/"+streamConfig.spark_config.stream_id,originalSourceNamespace);
    val dataSet=df.filter(row=>row!=null
      && row.length>0
      && paramUtil.getMyProjectId.equals(row.getAs[Long](SchemaUtils.KafkaOriginColumn.project_id.name()))
      && ConvertUtils.isEventType(row.getAs[String](SchemaUtils.KafkaOriginColumn.`type`.name())));
//    if(dataSet.count()==0){
//      return null;
//    }
    val schemaUtils=new SchemaUtils(paramUtil);
    schemaUtils.checkSensorSystemCompleteSchemaChange(paramUtil.getMyProjectId());
    schemaUtils.checkClickHouseSchemaNeedChange(paramUtil.getMyProjectId());
    schemaUtils.destroy();
    session.sessionState.conf.setConfString("processed_source_namespace",paramUtil.getNameSpace())
    val proColumnMap:util.Map[String,PropertyColumnEntry]=schemaUtils.getProColumnMap();
    val eventMap:util.Map[String,EventEntry]=schemaUtils.getEventMap();
    val sortedList:util.List[String]=schemaUtils.getPropertiesSortedList();
    val resultRowSchema=convertSchema(proColumnMap,sortedList);
    val resultRowRdd: RDD[Row] = dataSet.rdd.mapPartitions(it=>{
      val resultList = mutable.ListBuffer.empty[Row]
      val listRow:List[Row]=it.toList
      listRow.foreach(row=>{
           resultList+=covertRow(row,proColumnMap,eventMap,sortedList);
      })
      resultList.iterator;
    })
    val dataFrame=session.createDataFrame(resultRowRdd,resultRowSchema)
    //dataFrame.show(10000000)
    dataFrame

    //val jsonList=new util.ArrayList[String]();
    //dataSet.toJSON.foreach(x=>jsonList.add(x));
    //val newJsonList=ConvertUtils.covert(jsonList,propertySet,schemaUtils.getEventMap,schemaUtils.getProMap,schemaUtils.getColumnMap)
    //val ds=session.createDataset(newJsonList);
    //return session.read.json(ds)
    //val ds2 = session.createDataset[String](Seq(json1, json2))(Encoders.STRING).toDF();

//    val data=session.createDataset[String](l)
//    session.createDataset()
//    session.read.json(data)
    //dataSet.
    //session.read.json()
    //session.read.json(dataSet.toJSON.map(x=>{ConvertUtils.covert(x,propertySet,schemaUtils.getEventMap,schemaUtils.getProMap,schemaUtils.getColumnMap)}))

//    dataSet("x")+(null);
//
//    dataSet.toString()
//    dataSet.withColumn("f1",dataSet("ff0"))
//    val c=Column[String]("f",null,false);
//
//    dataSet.withColumn("f0",1L)
//
//    dataSet.drop("f1");
//    dataSet.drop("f2");
//    dataSet.drop("f3");
  }


  def covertRow(row:Row,columns:util.Map[String,PropertyColumnEntry],eventMap:util.Map[String,EventEntry],sortedList:util.List[String]):Row={
    val rowValue=ArrayBuffer[Any]();
    val fields=ArrayBuffer[StructField]();

    val _trick=row.getAs[Long](SchemaUtils.KafkaOriginColumn._track_id.name())
    rowValue +=ConvertUtils.calcSamplingGroup(row.getAs[Long](SchemaUtils.KafkaOriginColumn.user_id.name()))
    fields +=StructField("sampling_group",IntegerType,true)
    rowValue +=row.getAs[Long](SchemaUtils.KafkaOriginColumn.user_id.name())
    fields +=StructField("user_id",LongType,true)
    rowValue +=ConvertUtils.getOffset(_trick);
    fields +=StructField("_offset",LongType,true)
    val _time = row.getAs[Long](SchemaUtils.KafkaOriginColumn.time.name())
    rowValue +=ConvertUtils.calcDayId(new DateTime(_time).toLocalDateTime())
    fields +=StructField("day",IntegerType,true)
    rowValue +=ConvertUtils.calcWeekId(new DateTime(_time).toLocalDateTime())
    fields +=StructField("week_id",IntegerType,true)
    rowValue +=ConvertUtils.calcMonthId(new DateTime(_time).toLocalDateTime())
    fields +=StructField("month_id",IntegerType,true)
    val _distinct=row.getAs[String](SchemaUtils.KafkaOriginColumn.distinct_id.name())
    rowValue +=_distinct
    fields +=StructField("distinct_id",StringType,true)
    val _event=row.getAs[String](SchemaUtils.KafkaOriginColumn.event.name())
    val _event_id=if(eventMap.get(_event)==null) null else eventMap.get(_event).getId()
    val _event_bucket=if(eventMap.get(_event)==null) null else eventMap.get(_event).getBucket_id()
    rowValue +=_event_id
    fields +=StructField("event_id",IntegerType,true)
    rowValue +=_event_bucket
    fields +=StructField("event_bucket",IntegerType,true)
    rowValue +=_time
    fields +=StructField("time",LongType,true)
    rowValue +=ConvertUtils.dateTimeFormat(_time)
    fields +=StructField("ums_ts_",StringType,true)
    rowValue +=ConvertUtils.dateFormat(_time)
    fields +=StructField("event_date",StringType,true)
    rowValue +=AESUtil.decrypt(_distinct)
    fields +=StructField("yx_user_id",StringType,true)

//    val proRow:Row=row.getAs[Row](SchemaUtils.KafkaOriginColumn.properties.name())
//    val _arrayValues:Array[Any]=proRow.schema.fieldNames.map( x => {
//      proRow.getAs[Any](x)
//    })
//    val _newValues=_arrayValues.toBuffer
//    _newValues.add(ConvertUtils.getOffset(_trick))
//    _newValues.add(row.getAs[Long](SchemaUtils.KafkaOriginColumn.recv_time.name()))
//    val _newSchema:StructType=proRow.schema.
//      add(StructField("$kafka_offset",LongType,true))
//      .add(StructField("$receive_time",LongType,true))
//    val _newRow=new GenericRowWithSchema(_newValues.toArray,_newSchema)
    val json:String=row.getAs[String](SchemaUtils.KafkaOriginColumn.properties.name())
    val jsonObj: JSONObject=JSON.parseObject(json);
    jsonObj.put("$kafka_offset",ConvertUtils.getOffset(_trick))
    jsonObj.put("$receive_time",row.getAs[Long](SchemaUtils.KafkaOriginColumn.recv_time.name()))
    for(key<-sortedList){
      val _col:String=columns.get(key).getColumn_name()
      val _data_type:Int=columns.get(key).getData_type()
      val _type=_data_type match {
        case 1 =>LongType
        case 2 =>StringType
        case 3 =>StringType
        case 4 =>LongType
        case 5 =>LongType
        case 6 =>IntegerType
      }
      fields +=StructField(_col,_type,true)
      if(!jsonObj.keySet().contains(key)){
        rowValue +=null
      }else{
        val _value:Object=jsonObj.get(key)
        if(_value==null){
          rowValue +=null
        }else{
          rowValue +=ConvertUtils.convert(key,columns.get(key),_value)
        }
      }
    }
    return new GenericRowWithSchema(rowValue.toArray,StructType(fields))
  }


  def convertSchema(columns:util.Map[String,PropertyColumnEntry],sortedList:util.List[String]):StructType={
    val fields=ArrayBuffer[StructField]();
    fields +=StructField("sampling_group",IntegerType,true)
    fields +=StructField("user_id",LongType,true)
    fields +=StructField("_offset",LongType,true)
    fields +=StructField("day",IntegerType,true)
    fields +=StructField("week_id",IntegerType,true)
    fields +=StructField("month_id",IntegerType,true)
    fields +=StructField("distinct_id",StringType,true)
    fields +=StructField("event_id",IntegerType,true)
    fields +=StructField("event_bucket",IntegerType,true)
    fields +=StructField("time",LongType,true)
    fields +=StructField("ums_ts_",StringType,true)
    fields +=StructField("event_date",StringType,true)
    fields +=StructField("yx_user_id",StringType,true)
    for(key<-sortedList){
      val _col:String=columns.get(key).getColumn_name()
      val _data_type:Int=columns.get(key).getData_type()
      val _type=_data_type match {
        case 1 =>LongType
        case 2 =>StringType
        case 3 =>StringType
        case 4 =>LongType
        case 5 =>LongType
        case 6 =>IntegerType
        case _ =>StringType
      }
      fields +=StructField(_col,_type,true)
    }
    StructType(fields)
  }

}
