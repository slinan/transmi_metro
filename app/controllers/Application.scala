package controllers

import java.io.File
import javax.inject.Inject

import akka.actor.{Actor, ActorRef, PoisonPill, Props}
import play.api.Play.current
import play.api._
import play.api.libs.concurrent.Promise
import play.api.libs.iteratee.{Concurrent, Enumerator, Iteratee}
import play.api.libs.ws._
import play.api.mvc._
import java.util.concurrent.ConcurrentLinkedQueue

import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.io.Source


class Application @Inject()(ws: WSClient) extends Controller {

  // http endpoint to check that the server is running
  def index = Action {
    Ok("I'm alive!\n")
  }

  // sends the time every second, ignores any input
  def wsPingPong = WebSocket.using[String] {
    request =>
      Logger.info(s"wsPingPong, client connected.")
      var switch: Boolean = true
      StreamManager.queue.clear()
      Main.start()
      val outEnumerator = Enumerator.repeatM[String](Promise.timeout({
        StreamManager.poll()
      }, 30))

      (Iteratee.ignore[String], outEnumerator)
  }
}

object RoutesBusEnum extends Enumeration{
  type RoutesBusEnum = Value
  val GREEN_EAST, GREEN_WEST, RED_NORTH, RED_SOUTH, LONG_EAST, LONG_NORTH = Value
}

object StreamManager {
  var queue = new ConcurrentLinkedQueue[String]
  queue.add("Starting")
  def poll(): String = synchronized{queue.poll()}
  def add(str:String) = synchronized{
    queue.add(str)
  }
}

class Bus (nIdBus: Int,delay: Long, startingDelay: Long,nStations:List[Int],nSize:Int, startAgainDelay: Long) extends Runnable{
  def size:Int = nSize
  def idBus:Int = nIdBus
  def stations:List[Int]=nStations
  def numberStations: Int = stations.length
  var ocupation: Int = 0

  var queues: mutable.LinkedHashMap[Int,Int] = new mutable.LinkedHashMap()
  for (i <- 0 to 14) {
    queues.put(i+1,0)
  }

  def direction():Int = {
    if (nStations(1) > nStations(2)) {
      -1
    }
    1
  }

  def isStationNext(station: Int, stationNext: Int): Boolean =
  {
    if((direction== 1 && station < stationNext) || (direction== -1 && station > stationNext))
      {
        true
      }
    else if((direction== 1 && station > stationNext) || (direction== -1 && station < stationNext))
      {
        false
      }

    true

  }

  def run: Unit = {
    Thread.sleep(startingDelay)

    while(Main.runTrains) {
      StreamManager.add("TRAIN_STATUS:" + idBus + ":On Route")
      for (currentStation <- nStations) {
        var station: Station = Main.stationsMap(currentStation)
        StreamManager.add("ARRIVED:" + idBus + ":" + currentStation)


        //Pick up passengers

        for (nextStation <- nStations if size >= ocupation && isStationNext(currentStation, nextStation)) {
          var newUsers: Int = station.pickUp(nextStation, size - ocupation)

          if (newUsers > 0) {
            addUsers(newUsers, nextStation)
          }
        }

        //Leave passengers
        var leavingUsers: Int = queues(currentStation)
        queues.put(currentStation, 0)
        ocupation -= leavingUsers
        StreamManager.add("TRAIN:" + idBus + ":" + ocupation)
        station.addLeavingUsers(leavingUsers)

        //Time between stations
        Thread.sleep(delay)
      }

      StreamManager.add("TRAIN_STATUS:" + idBus + ":Returning to initial station")
      Thread.sleep(startAgainDelay)
    }
  }

  def addUsers(number:Int, destination: Int): Boolean =
  {
      ocupation += number
      queues(destination) += number
      StreamManager.add("TRAIN:" + idBus + ":" + ocupation)
  }
}

class Station (nIdStation: Int) extends Runnable
{
  def idStation:Int = nIdStation

  var queues: mutable.LinkedHashMap[Int, Int] = new mutable.LinkedHashMap()
  var users = 0
  var usersLeaving = 0
  var usersComing = 0
  for (i <- 0 to 14) {
    queues.put(i+1,0)
  }

  var queueLeaving: ConcurrentLinkedQueue[People] = new ConcurrentLinkedQueue[People]()

  def addUsers(number:Int, destination: Int): Unit = synchronized
  {
    usersComing +=number
    if(destination == idStation)
    {
      deleteUsers(number)
    }
    else {
      queues(destination) += number
      users += number
      StreamManager.add("STATION:" + idStation + ":" + users)
    }
  }

  def deleteUsers(number: Int): Unit = synchronized
  {
    users -= number
    usersLeaving += number
    StreamManager.add("STATION:"+idStation+":"+users)
  }

  def addLeavingUsers(number: Int): Unit = synchronized
  {
    usersLeaving += number
    StreamManager.add("STATION:"+idStation+":"+users)
  }

  def pickUp(destination:Int, availableSeats:Int): Int = synchronized
  {
    var passengers = 0
    if(availableSeats >= queues(destination)) {
      passengers = queues(destination)
    }
    else {
      passengers = availableSeats
    }

    queues(destination) -=  passengers
    users -= passengers
    usersLeaving += passengers
    passengers
  }


  def run: Unit = {

    var count: Int = 0
    while(Main.runTrains) {


      var people: People = queueLeaving.peek()
      while (count == people.minutoLlegada)
        {
          queueLeaving.poll()
          addUsers(people.numPersonas, people.destino)
          people = queueLeaving.peek()
        }
      count += 1

      Thread.sleep(Main.baseUnit)
    }
    }

  def reportStatistics: Unit =
  {
    StreamManager.add("REPORT:"+idStation+":"+users+":"+usersComing+":"+usersLeaving)
    usersComing = 0
    usersLeaving = 0
  }

}

class Reporter(time:Long) extends Runnable {

  def run: Unit = {

    while (true) {
      Thread.sleep(time)
      Main.stationsMap.foreach(_._2.reportStatistics)
    }

  }
}

class People(pNumPersonas: Int, pDestino: Int, pOrigen: Int, pMinutoLlegada: Int )
{
  def numPersonas:Int = pNumPersonas
  def destino:Int = pDestino
  def origen:Int = pOrigen
  def minutoLlegada = pMinutoLlegada

}


object Main {
  val route1: List[Int] = List[Int](1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
  val route2: List[Int] = List[Int](10, 9, 8, 7, 6, 5, 4, 3, 2, 1)
  val route3: List[Int] = List[Int](1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15)
  val route4: List[Int] = List[Int](15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1)
  val route5: List[Int] = List[Int](15, 14, 13, 12, 11, 10)
  val route6: List[Int] = List[Int](10, 11, 12, 13, 14, 15)

  val baseUnit = 4000
  val delay: Long = 4*baseUnit
  val reportDelay: Long = 10*baseUnit
  val startAgainDelay = 10*baseUnit

  var busesMap: mutable.LinkedHashMap[Int,Bus] = new mutable.LinkedHashMap()
  var stationsMap: mutable.LinkedHashMap[Int, Station] = mutable.LinkedHashMap()
  var runTrains: Boolean = true

  def start(): Unit = {
    println("START MAIN")
    createStationsMap()
    readPeople()
    startStations()
    readBuses()
    new Thread(new Reporter(reportDelay)).start()
  }

  def createStationsMap(): Unit = {
    for (i <- 0 to 14) {
      stationsMap.put(i+1,new Station((i + 1)))
    }
  }

  def startStations(): Unit = {
    for (i <- 0 to 14) {
      new Thread (stationsMap(i+1)).start()
    }
  }

  def readPeople(): Unit = {
    val bufferedSource = Source.fromFile("filePeople.csv")
    for (line <- bufferedSource.getLines) {
      val cols = line.split(",").map(_.trim)
      var numPersonas: Int = 0
      var destino: Int = 0
      var origen: Int = 0
      var minutoLlegada: Int = 0
      if(!cols(0).contains("num_personas")) {
        numPersonas = cols(0).toInt
        destino = cols(1).toInt
        origen = cols(2).toInt
        minutoLlegada = cols(3).toInt
        if(origen != destino) {
          stationsMap(origen).queueLeaving.add(new People(numPersonas, destino, origen, minutoLlegada))
        }
      }
      }
  }

  def readBuses(): Unit = {
    val bufferedSource = Source.fromFile("busSchedules.csv")
    for (line <- bufferedSource.getLines) {
      val cols = line.split(",").map(_.trim)
      // do whatever you want with the columns here
      if(!cols(0).contains("busID")) {

        var size: Int = 900
        if (cols(0).toInt > 11) {
          size = 1800
        }

        if (cols(1).toInt == 1 && cols(2).toInt == 10) {
          busesMap.put(cols(0).toInt, new Bus(cols(0).toInt, delay, cols(3).toLong * baseUnit, route1, size, startAgainDelay))
          new Thread (busesMap(cols(0).toInt)).start()
        }
        else if (cols(1).toInt == 10 && cols(2).toInt == 1) {
          busesMap.put(cols(0).toInt, new Bus(cols(0).toInt, delay, cols(3).toLong * baseUnit, route2, size, startAgainDelay))
          new Thread (busesMap(cols(0).toInt)).start()
        }
        else if (cols(1).toInt == 1 && cols(2).toInt == 15) {
          busesMap.put(cols(0).toInt, new Bus(cols(0).toInt, delay, cols(3).toLong * baseUnit, route3, size, startAgainDelay))
          new Thread (busesMap(cols(0).toInt)).start()
        }
        else if (cols(1).toInt == 15 && cols(2).toInt == 1) {
          busesMap.put(cols(0).toInt, new Bus(cols(0).toInt, delay, cols(3).toLong * baseUnit, route4, size, startAgainDelay))
          new Thread (busesMap(cols(0).toInt)).start()
        }
        else if (cols(1).toInt == 10 && cols(2).toInt == 15) {
          busesMap.put(cols(0).toInt, new Bus(cols(0).toInt, delay, cols(3).toLong * baseUnit, route6, size, startAgainDelay))
          new Thread (busesMap(cols(0).toInt)).start()
        }
        else if (cols(1).toInt == 15 && cols(2).toInt == 10) {
          busesMap.put(cols(0).toInt, new Bus(cols(0).toInt, delay, cols(3).toLong * baseUnit, route5, size, startAgainDelay))
          new Thread (busesMap(cols(0).toInt)).start()
        }
      }
    }
    bufferedSource.close
  }



}

