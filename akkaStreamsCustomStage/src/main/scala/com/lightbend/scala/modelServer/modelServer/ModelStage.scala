package com.lightbend.scala.modelServer.modelServer

import akka.stream._
import akka.stream.stage.{GraphStageLogicWithLogging, _}
import com.lightbend.model.winerecord.WineRecord
import com.lightbend.scala.modelServer.model.{Model, ModelToServeStats, ModelWithDescriptor}


class ModelStage extends GraphStageWithMaterializedValue[ModelStageShape, ReadableModelStateStore] {

  val dataRecordIn = Inlet[WineRecord]("dataRecordIn")
  val modelRecordIn = Inlet[ModelWithDescriptor]("modelRecordIn")
  val scoringResultOut = Outlet[Option[Double]]("scoringOut")

  override val shape: ModelStageShape = new ModelStageShape(dataRecordIn, modelRecordIn, scoringResultOut)

  class ModelLogic(shape: ModelStageShape) extends GraphStageLogicWithLogging(shape) {
    // state must be kept in the Logic instance, since it is created per stream materialization
    private var currentModel: Option[Model] = None
    private var newModel: Option[Model] = None
    var currentState: Option[ModelToServeStats] = None // exposed in materialized value
    private var newState: Option[ModelToServeStats] = None

    override def preStart(): Unit = {
      tryPull(modelRecordIn)
      tryPull(dataRecordIn)
    }

    setHandler(modelRecordIn, new InHandler {
      override def onPush(): Unit = {
        val model = grab(modelRecordIn)
        newState = Some(new ModelToServeStats(model.descriptor))
        newModel = Some(model.model)
        pull(modelRecordIn)
      }
    })

    setHandler(dataRecordIn, new InHandler {
      override def onPush(): Unit = {
        val record = grab(dataRecordIn)
        newModel match {
          case Some(model) => {
            // close current model first
            currentModel match {
              case Some(m) => m.cleanup()
              case _ =>
            }
            // Update model
            currentModel = Some(model)
            currentState = newState
            newModel = None
          }
          case _ =>
        }
        currentModel match {
          case Some(model) => {
            val start = System.currentTimeMillis()
            val quality = model.score(record.asInstanceOf[AnyVal]).asInstanceOf[Double]
            val duration = System.currentTimeMillis() - start
            println(s"Calculated quality - $quality calculated in $duration ms")
            currentState.get.incrementUsage(duration)
            push(scoringResultOut, Some(quality))
          }
          case _ => {
            println("No model available - skipping")
            push(scoringResultOut, None)
          }
        }
        pull(dataRecordIn)
      }
    })

    setHandler(scoringResultOut, new OutHandler {
      override def onPull(): Unit = {
      }
    })
  }

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, ReadableModelStateStore) = {
    val logic = new ModelLogic(shape)

    // we materialize this value so whoever runs the stream can get the current serving info
    val readableModelStateStore = new ReadableModelStateStore() {
      override def getCurrentServingInfo: ModelToServeStats = logic.currentState.getOrElse(ModelToServeStats.empty)
    }
    new Tuple2[GraphStageLogic, ReadableModelStateStore](logic, readableModelStateStore)
  }
}

class ModelStageShape(val dataRecordIn: Inlet[WineRecord], val modelRecordIn: Inlet[ModelWithDescriptor], val scoringResultOut: Outlet[Option[Double]]) extends Shape {

  override def deepCopy(): Shape = new ModelStageShape(dataRecordIn.carbonCopy(), modelRecordIn.carbonCopy(), scoringResultOut)

  override val inlets = List(dataRecordIn, modelRecordIn)
  override val outlets = List(scoringResultOut)

}
