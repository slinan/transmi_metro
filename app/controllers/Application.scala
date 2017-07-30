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

/**
  * Main application
  * @param ws Injected web socket
  */
class Application @Inject()(ws: WSClient) extends Controller {

  // HTTP endpoint to check that the server is running
  def index = Action {
    Ok("OK\n")
  }

  // Sends the content of the main queue
  def socket = WebSocket.using[String] {
    request =>
      Logger.info(s"Client Connected")
      StreamManager.queue.clear()
      Main.start()
      val outEnumerator = Enumerator.repeatM[String](Promise.timeout({
        StreamManager.poll()

      }, 20))

      (Iteratee.ignore[String], outEnumerator)
  }
}

/**
  * Manages the stream of events that the socket will read
  */
object StreamManager {
  var queue = new ConcurrentLinkedQueue[String]
  queue.add("Starting")
  def poll(): String = synchronized{queue.poll()}
  def add(str:String) = synchronized{queue.add(str)
  }
}

/**
  * Represents a bus inside the system. Extends Runnable
  * @param pIdTrain ID of the train
  * @param pDelay Delay of the train between stations
  * @param pStartingDelay Delay to start the train
  * @param pStations Array of the stations that the train will go over
  * @param pSize Maximum size of the train
  * @param pStartAgainDelay Delay between the train stops its route and starts again
  */
class Bus (pIdTrain: Int, pDelay: Long, pStartingDelay: Long, pStations:List[Int], pSize:Int, pStartAgainDelay: Long) extends Runnable{
  def size:Int = pSize
  def idTrain:Int = pIdTrain
  def stations:List[Int]=pStations
  var occupation: Int = 0 //Ocupation of the train

  //Linked hash map of the queues. There is a queue for each train station
  var queues: mutable.LinkedHashMap[Int,Int] = new mutable.LinkedHashMap()
  for (i <- 0 to 14) {
    queues.put(i+1,0)
  }

  /**
    * Defines the direction of the train.
    * @return 1 if goes from East to North or East to West, -1 if goes from North to East or West to East
    */
  def direction():Int = {
    if (pStations(1) > pStations(2)) {-1}
    1
  }

  /**
    * Defines if a station is after another, given the direction of the train
    * @param station First station to test
    * @param stationNext Second station to test
    * @return true if stationNext if after station, false otherwise
    */
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

  /**
    * Runnable method of the bus
    */
  def run: Unit = {
    Thread.sleep(pStartingDelay) //Waits the starting delay

    while(Main.runTrains) {
      StreamManager.add("TRAIN_STATUS:" + idTrain + ":On Route") //Prints in the queue On Route state
      //Walks for every station inside the list
      for (currentStation <- pStations if Main.runTrains) {
        var station: Station = Main.stationsMap(currentStation)
        StreamManager.add("ARRIVED:" + idTrain + ":" + currentStation) //Alert that the train has arrived

        //Pick up passengers
        for (nextStation <- pStations if size >= occupation && isStationNext(currentStation, nextStation) if Main.runTrains) {
          var newUsers: Int = station.pickUp(nextStation, size - occupation)
          if (newUsers > 0) {addUsers(newUsers, nextStation)}
        }

        //Leave passengers in the station
        var leavingUsers: Int = queues(currentStation)
        queues.put(currentStation, 0)
        occupation -= leavingUsers
        StreamManager.add("TRAIN:" + idTrain + ":" + occupation) //Alert the train's occupation
        station.addLeavingUsers(leavingUsers)

        //Time between stations
        Thread.sleep(pDelay)
      }

      // If the train ends its route, the train returns to its home station
      StreamManager.add("TRAIN_STATUS:" + idTrain + ":Returning to initial station")
      Thread.sleep(pStartAgainDelay)
    }
  }

  /**
    * Add a number of users to its waiting queue
    * @param number Number of users that will be added
    * @param destination Destination of the users group
    */
  def addUsers(number:Int, destination: Int): Unit =
  {
      occupation += number
      queues(destination) += number
      StreamManager.add("TRAIN:" + idTrain + ":" + occupation)
  }
}

/**
  * Represents a station inside the system
  * @param pIdStation Id of the station
  */
class Station (pIdStation: Int) extends Runnable
{
  def idStation:Int = pIdStation

  //Linked hash map of waiting queues inside the station
  var queues: mutable.LinkedHashMap[Int, Int] = new mutable.LinkedHashMap()
  var users = 0
  var usersLeaving = 0
  var usersComing = 0
  for (i <- 0 to 14) {
    queues.put(i+1,0)
  }

  //Concurrent queue of people entering the station
  var queueEntering: ConcurrentLinkedQueue[People] = new ConcurrentLinkedQueue[People]()

  /**
    * Adds a number of user to a specific destination
    * @param pNumber Number of users to be added
    * @param pDestination Destination of the users
    */
  def addUsers(pNumber:Int, pDestination: Int): Unit = synchronized
  {
    usersComing +=pNumber
    if(pDestination == idStation) {deleteUsers(pNumber)} //If home station is the same destination, the user has arrived.
    else {
      queues(pDestination) += pNumber
      users += pNumber
      StreamManager.add("STATION:" + idStation + ":" + users)
    }
  }

  /**
    * Delete the people from the system
    * @param pNumber Number of people to be deleted
    */
  def deleteUsers(pNumber: Int): Unit = synchronized
  {
    users -= pNumber
    usersLeaving += pNumber
    StreamManager.add("STATION:"+idStation+":"+users)
  }

  /**
    * Sums the leaving users counter
    * @param pNumber Number of users that will be leaving
    */
  def addLeavingUsers(pNumber: Int): Unit = synchronized
  {
    usersLeaving += pNumber
    StreamManager.add("STATION:"+idStation+":"+users)
  }

  /**
    * PickUp a number of passengers given a number of available seats
    * @param pDestination Destination of the users
    * @param pAvailableSeats Available places in the bus
    * @return Number of passengers that will leave the station
    */
  def pickUp(pDestination:Int, pAvailableSeats:Int): Int = synchronized
  {
    var passengers = 0
    if(pAvailableSeats >= queues(pDestination)) {
      passengers = queues(pDestination)
    }
    else {
      passengers = pAvailableSeats
    }

    queues(pDestination) -=  passengers
    users -= passengers
    usersLeaving += passengers
    passengers
  }

  // Run method of the thread
  def run: Unit = {

    var count: Int = 0
    while(Main.runTrains) {

      var people: People = queueEntering.peek()
      while (people != null && count == people.minutoLlegada && Main.runTrains)
        {
          queueEntering.poll()
          addUsers(people.numPersonas, people.destino)
          people = queueEntering.peek()
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

/**
  * Reports the system statics
  * @param pTime Time between reports
  */
class Reporter(pTime:Long) extends Runnable {

  def run: Unit = {

    while (Main.runTrains) {
      Thread.sleep(pTime)
      Main.stationsMap.foreach(_._2.reportStatistics)
    }

  }
}

/**
  * Representation of a entry in the CSV file
  * @param pNumPeople Number of people
  * @param pDestination Destination station
  * @param pOrigin Origin station
  * @param pTime Minute when the users will arrive
  */
class People(pNumPeople: Int, pDestination: Int, pOrigin: Int, pTime: Int )
{
  def numPersonas:Int = pNumPeople
  def destino:Int = pDestination
  def origen:Int = pOrigin
  def minutoLlegada = pTime

}

/**
  * Main object that contains the simulation
  */
object Main {

  /**
    * Kill the simulation when the signal is received
    */
  def killAll(): Unit = {
    runTrains = false
    var i: Int = 0
    while(i<40) {
      threads.poll().interrupt()
      i+=1
    }
  }

  /**
    * Threads array
    * @return Array of threads
    */
  def threads: ConcurrentLinkedQueue[Thread] =new ConcurrentLinkedQueue[Thread] ()

  //Possible routes of the trains
  val route1: List[Int] = List[Int](1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
  val route2: List[Int] = List[Int](10, 9, 8, 7, 6, 5, 4, 3, 2, 1)
  val route3: List[Int] = List[Int](1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15)
  val route4: List[Int] = List[Int](15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1)
  val route5: List[Int] = List[Int](15, 14, 13, 12, 11, 10)
  val route6: List[Int] = List[Int](10, 11, 12, 13, 14, 15)
  val baseUnit = 4000
  val delay: Long = 4*baseUnit
  val reportDelay: Long = 3*baseUnit
  val startAgainDelay = 8*baseUnit

  //Hash map of buses
  var busesMap: mutable.LinkedHashMap[Int,Bus] = new mutable.LinkedHashMap()
  //Hash map of stations
  var stationsMap: mutable.LinkedHashMap[Int, Station] = mutable.LinkedHashMap()
  var runTrains: Boolean = true

  /**
    * Starts the simulation
    */
  def start(): Unit = {
    println("START MAIN")
    createStationsMap()
    readPeople()
    startStations()
    readBuses()
    var thReporter: Thread = new Thread(new Reporter(reportDelay))
    thReporter.start()
    threads.add(thReporter)
  }

  /**
    * Create the map of stations
    */
  def createStationsMap(): Unit = {
    for (i <- 0 to 14) {
      stationsMap.put(i+1,new Station((i + 1)))
    }
  }

  /**
    * Starts the stations threads
    */
  def startStations(): Unit = {
    for (i <- 0 to 14) {
      var thStation: Thread = new Thread (stationsMap(i+1))
      thStation.start()
      threads.add(thStation)
    }
  }

  /**
    * Read people from the CSV file
    */
  def readPeople(): Unit = {
    val bufferedSource = Source.fromFile("filePeople.csv")
    for (line <- bufferedSource.getLines) {
      val cols = line.split(",").map(_.trim)
      var numPeople: Int = 0
      var destination: Int = 0
      var origin: Int = 0
      var trainArrival: Int = 0
      if(!cols(0).contains("num_personas")) {
        numPeople = cols(0).toInt
        destination = cols(1).toInt
        origin = cols(2).toInt
        trainArrival = cols(3).toInt
        if(origin != destination) {
          stationsMap(origin).queueEntering.add(new People(numPeople, destination, origin, trainArrival))
        }
      }
      }
  }

  /**
    * Read the buses CSV file
    */
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
          var th: Thread = new Thread (busesMap(cols(0).toInt))
          th.start()
          threads.add(th)
        }
        else if (cols(1).toInt == 10 && cols(2).toInt == 1) {
          busesMap.put(cols(0).toInt, new Bus(cols(0).toInt, delay, cols(3).toLong * baseUnit, route2, size, startAgainDelay))
          var th: Thread = new Thread (busesMap(cols(0).toInt))
          th.start()
          threads.add(th)        }
        else if (cols(1).toInt == 1 && cols(2).toInt == 15) {
          busesMap.put(cols(0).toInt, new Bus(cols(0).toInt, delay, cols(3).toLong * baseUnit, route3, size, startAgainDelay))
          var th: Thread = new Thread (busesMap(cols(0).toInt))
          th.start()
          threads.add(th)        }
        else if (cols(1).toInt == 15 && cols(2).toInt == 1) {
          busesMap.put(cols(0).toInt, new Bus(cols(0).toInt, delay, cols(3).toLong * baseUnit, route4, size, startAgainDelay))
          var th: Thread = new Thread (busesMap(cols(0).toInt))
          th.start()
          threads.add(th)        }
        else if (cols(1).toInt == 10 && cols(2).toInt == 15) {
          busesMap.put(cols(0).toInt, new Bus(cols(0).toInt, delay, cols(3).toLong * baseUnit, route6, size, startAgainDelay))
          var th: Thread = new Thread (busesMap(cols(0).toInt))
          th.start()
          threads.add(th)        }
        else if (cols(1).toInt == 15 && cols(2).toInt == 10) {
          busesMap.put(cols(0).toInt, new Bus(cols(0).toInt, delay, cols(3).toLong * baseUnit, route5, size, startAgainDelay))
          var th: Thread = new Thread (busesMap(cols(0).toInt))
          th.start()
          threads.add(th)        }
      }
    }
    bufferedSource.close
  }
}

