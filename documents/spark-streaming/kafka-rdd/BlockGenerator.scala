// Reference: https://github.com/apache/spark/blob/5264164a67df498b73facae207eda12ee133be7d/streaming/src/main/scala/org/apache/spark/streaming/receiver/BlockGenerator.scala

package org.apache.spark.streaming.receiver 

import java.util.concurrent.{ArrayBlockingQueue, TimeUnit}
import scala.collection.mutable.ArrayBuffer 
import org.apache.spark.{SparkConf, SparkException}
import org.apache.spark.internal.Logging 
import org.apache.spark.storage.StreamBLockId 
import org.apache.spark.streaming.util.RecurringTimer 
import org.apache.spark.util.{Clock, SystemClock}


// --- 这里的代码阅读顺序建议先阅读 BlockGenerator 类中的对 5 中状态的注释信息, 了解不同状态都会执行那些方法 ---
// --- 在了解 5 中状态, 5 中状态下执行的不同操作后, 再来阅读 BlockGeneratorListener 中所定义的不同方法所触发/回调的时机, 会有更好的理解 --- 


// ---- class of BlockGeneratorListener --- 

// Listener object for BlockGenerator events 
// BlockGenerator 中会维护一个状态机, 在状态机位于不同状态调用某个方法之后, 会顺带通过传入的实现了 BlockGeneratorListener 接口类实例
// 中对应的方法, 我们可以通过 extends BlockGeneratorListener 的方式, 在类中根据需要来实现相关的逻辑, 
// 然后, BlockGenerator 里创建 Block 期间, 调用不同的 BlockGeneratorListener 中的方法, 
// 这些方法虽然是定义在 BlockGeneratorListener 中, 但是具体实现逻辑是由我们来实现的, 
// 通过 BlockGeneratorListener 接口, 我们和配合 BlockGenerator 中不同构建 Block 的时机来通过 Listener 中的函数操作加到对应的时间点中来执行
// 原本写到这里的 Event 的这种处理方法, 是我当时类比 SparkListenerBus/SparkListener 中对 Event 进行监听的方式和注释信息中所提交到的 Event 的猜测的,
// 但是, 仔细看过 BlockGenerator 相关方法之后, 会发现, BlockGenerator 中并没有特别明显的 Event 的处理方法, 
// 而是通过状态机不同调度时间点来触发调用 Listener 中的方法的, 或许这也算作是一种事件驱动的调用方式 ? 毕竟是状态机切换的, 也姑且算作是事件驱动吧~ 
// 
// -- 
// 不过细想一下和我之前了解到的
// ===> zookeeper 上的对不同路径节点所设定的 Watcher 的调用方法也和这里的事件驱动方式类似, 只不过 ZK 上 Watcher 所监听的事件控制在监听路径的,增,删,改,查 这四种状态
// 这个差不多, 可能是我还没看 SparkListenerBus 这里的实现方式吧

private[streaming] trait BlockGeneratorListener {

    // Call after a data item is added into the BlockGenerator.
    // 当 BlockGenerator 中加入数据项时, BlockGeneratorListener 中的 onAddData 这个函数会作为该事件的回调函数而被调用.

    // The data addition and this callback are synchronized with the block generation and its associated callback,
    // so block generation waits for the active data addition+callback to complete. 
    // BlockGenerator 中数据项增加操作, 及该操作所触发的回调函数与生成数据块/block 操作, 及该操作所触发的一系列回调方法的执行时串行的关系,
    // 所以, 知道所有的数据项增加操作及该操作触发的回调方法全部执行完毕, 数据块/block 才能创建完成. 

    // This is useful for updating metdata on successful buffering of a data item, 
    // specificially that metadata that will be useful when a block is generated.
    // onAddData 方法对于更新描述尚在缓冲区中的数据项的元数据信息十分有用, 
    // 尤其是对于那些需要参照元数据中的信息的数据块. 

    // Any long blocking operation in this callback will hurt the throughput.
    // onAddData 回调方法中任何长时间的阻塞操作, 都会对整个流系统的吞吐性能造成重大影响.

    // onAddData 这个方法在 BlockGenerator 类中的调用时间点是, 在 1 个 blockInterval 时间周期内
    // 从上游数据 1 个 batchInterval 时间粒度内抓取一块数据的时候, 数据的数据空间被追加到正在构建的 Block 的缓存空间内, 
    // 通过 onAddData 来将这个 batchInterval 时间内抓取的数据块中的 数据块空间:ArrayBuffer[T], metadata:[TopicAndPartition, Long] 
    // 传递给 extends BlockGeneratorListener 的实现类中, 而 BlockGeneratorListener 的实现类让代码提交者以 private final class 
    // 的方式放到了自己加持各种优化 buff 的 KafkaReliableReceiver 同一个文件中所定义的 GeneratedBlockHandler 这个类中
    // 这个类中维护了 2 个缓存空间(HashMap): 
    // 缓存空间 1 topicParitionOffsetMap:mutable.HashMap[TopicAndPartition, Long] 
    // 缓存空间 2 blockOffsetMap:ConcurrentHashMap[StreamBlockId, Map[TopicAndPartition, Long]]
    // 缓存空间 1 用来存放 1 个正在构建中的 Block 中所有到来的 batch 消费上游 Kafka 对应 Topic.Partition.Offset 的消费进度, 每个 Topic.Partiton 的 Offset 仅会维护当前最新消费到的 Offset 的数值, 每次覆盖更新
    // 缓存空间 2 所存放的是每个已经构建好但尚未被 BlockGenerator 中的线程发送到 BlockManager 的所有 Block 的所有消费的上游的 Topic.Partition.Offset 的集合信息
    // 而这里的 onAddData 方法主要涉及到的便是缓冲空间 1 topicPartitionOffsetMap:mutable.HashMap[TopicAndPartition, Long]
    def onAddData(data:Any, metadata:Any):Unit 


    // Called when a new block of data is generated by the block generator. 
    // onGenerateBlock 函数每当 1 个 Block 对象被构建出来的时候便会被调用

    // The block generation and this callback are synchronized with the data addition and its associated callback, 
    // so the data addition waits for the block generation + callback to complete. 
    // BlockGeneration 类中构建 Block 对象的方法实际上没有任何有用的操作, 实际的处理逻辑都放到了 BlockGeneratorListener 的实现类中来完成, 
    // 而对应调用 BlockGeneratorListener 实现类中的函数便是下面这个 onGenerateBlock 的这个方法, 在实际运行环境中, BlockGeneration 构建方法
    // 和这个 onGenerateBlock 方法 和 前面的 onAddData 方法都是串行调用的, 每次 Listener 中的方法只有一个处于调用状态, 另一个要调用
    // 必须等待前一个调用完成才行, 只有 Block 通过回调函数 onGenerateBlock 构建完毕, onAddData 才能执行, 这也就说明了在构建 Block 的过程中
    // 不会从上游数据源拉取数据来继续构建新的 Block 
    def onGenerateBlock(blockId:StreamBlockId):Unit 

    // Called when a new block is ready to be pushed. Callers are supposed to store the block into Spark in this method. 
    // 下面的这个 onPushBlock 函数会在 BlockGenerator 刚构建好一个 Block 实例对象之后被调用, 这个回调函数中应该执行的逻辑是将 Block 存放到 Spark 中. (其实就是将 Block 对象发送给 Spark 对象的 BlockManager 来维护)
    
    // Internally this is called from a single thread, that is not synchronized with any other callbacks. 
    // 在内部这个回调函数会由一个单独的线程发起(就是那个 BlockGenerator 中每次负责将队列中的 Block 推送到 BlockManager 中的那个线程) 
    // 因为是 1 个线程执行的操作, 所以并不会存在一些因为线程并发执行所带来的问题.

    // Hence it is okay to do long blocking operation in this callback. 
    // 所以, 虽然消除了并发执行导致问题, 但却要特别注意下, 不要实现一些长时间阻塞线程的方法, 不然会成为整个代码测瓶颈. 
    def onPushBlock(blockId:StreamBlockId, arrayBuffer:ArrayBuffer[_]):Unit 

    // Called when an error has occured in the BlockGenerator. Can be called from many places 
    // so better not to do any long block operation in this callback. 
    // 这个方法封装了遇到问题会触发执行的一系列方法, 可以在 BlockGenerator 任何地方都可以作为回调方法来调用
    // 所以也最好注意下, 里面别加一些长时间阻塞的方法.
    def onError(message:String, throwable:Throwable):Unit 
}


// -----  class of BlockGenerator ----- 
/**
  Generates batches of objects received by a [[org.apache.spark.streaming.receiver.Receiver]] and puts them into appropriately
  named blocks at regular intervals.
  按照一定时间间隔将从 [[org.apache.spark.streaming.receiver.Receiver]] 多个 batch 接收到的对象汇聚在一起构建成数据块.
  (原来 1 个batch 接收到的数据对象未必构建 1 个 block, 多少个 batch 构建一个 block 对象, 这个是由设定的 block.interval 这个参数来决定的.
  从之前阅读反压(backpressure) 这个设计文档来看, 这个 regular interval 应该就是参数 block.interval 这个可由 spark-conf 传参设定的数值)
  所以, 应该是 1 block.interval = [1 - n 个 batch] , 1 block = [1 - n 个 batch 时间周期内接收到的 object 集合构成]

  This class starts two threads, one to periodically start a new batch and prepare the previous batch of as a block,
  the other to push the blocks into the block manager. 
  BlockGenerator 类中会启动 2 个线程, 一个负责周期性地开启读取新 batch/处理批次到达的数据, 然后将先前 batch/处理批次到达的数据构建成 1 个 block,
  另一个线程则负责将 1 到多个 block 推送到 block manager 中, 把不同批次达到的数据所构建的 block/数据块托管到 BlockManager 对象中去.
   

  Note: Do not create BlockGenerator instances directly inside receivers.
  Use `ReceiverSupervisor.createBlockGenerator` to create a BlockGenerator and use it. 
  需要注意的是: 不要在 receiver 中直接 new BlockGenerator 来创建实例对象. 
  使用 ReceiverSupervisor.createBlockGenerator 方法来创建 BlockGenerator 实例, 然后调用其方法.
*/
private[streaming] class BlockGenerator (
	listener:BlockGeneratorListener,  // 此处所传入的是实现了 BlockGeneratorListener 中所描述的方法的实体类对象
	receiverId:Int, 
	conf:SparkConf,
	clock:Clock = new SystemClock() // 构建系统时钟对象
	) extends RateLimiter(conf) with Logging {
	 // 这里的 RateLimiter 是用于控制 Spark 作为上游数据流的消费订阅者消费速率的类, 其构造函数中
	 // 需要传入 SparkConf 实例, 通过加载该实例中的 'spark.streaming.receiver.maxRate' 
	 // 这个参数中的数值, 来作为限流的参数, 这个是速率  rate,
	 // rate * block.interval = 一个周期内 spark streaming 所接收到的数据条数,也就是,数据密度(单位时间到达数据条数)
     
     // Block 是由一个 StreamBlockId 作为该 Block 的唯一标识, 和一块内存缓冲数据块构成
     private case class Block(id:StreamBlockId, buffer:ArrayBuffer[Any])

     // The BlockGenerator can be in 5 states, in the order as follows: 
     // BlockGenerator 是一个包含 5 个状态的状态机, (不过状态机的状态切换逻辑并不复杂) 5 种状态是依次按顺序切换的, 描述如下: 
     // ----
     // Q: 状态机, Event, 监听/Listener 这 3 者是怎样的调用关系? 
     // ---- 
     // - Initialized: 初始状态, 开始阶段什么都不执行
     
     // - Active: start() has been called, and it is generating blocks on added data.
     // - 活跃状态: start() 方法已经被调用, 开始基于从上游不断加载进来的数据生成数据块/block 

     // - StoppedAddingData: stop() has been called, the adding of data has been stopped, but blocks are still being generated and pushed. 
     // - 停止加载上游数据状态: stop() 这个方法被调用了, 已经停止从上游数据加载数据, 但是数据块还在不断生成, 并将生成完毕的数据块推送到 BlockManager 中, 交付给 BlockManager 管理. 

     // - StoppedGeneratingBlocks: Generating of blocks has been stopped, but they are still being pushed. 
     // - 停止构建数据块状态: 数据块的构建被停止, 但是被构建的数据块仍可以逐个被推送到 BlockManager 端. 

     // - StoppedAll: Everything has been stopped, and the BlockGenerator object can be GCed. 
     // - 终止所有操作状态: 所有操作均被终止, 这个时候 BlockGenerator 对象实例所占用的资源可通过 GC 垃圾回收方法回收释放.

     // 在这里创建一个枚举类型, 来标识 5 个不同的状态
     private object GeneratorState extends Enumeration {
         type GeneratorState = Value 
         val Initialized, Active, StoppedAddingData, StoppedGeneratingBlocks, StoppedAll = Value 
     }

     import GeneratorState._ 

     // 从传入的参数  conf:SparkConf 中加载 block.interval 这个配置项的数值, 如果没有设置则返回 200ms 这个数值
     // 这个也就是前文注释中提到的 block.interval 这个参数的全称了
     private val blockIntervalMs = conf.getTimeAsMs("spark.streaming.blockInterval", "200ms") 
     
     // 到这里再次确认下 block.interval 这个数值为正, 否则打出提示信息
     require(blockIntervalMs > 0, s"'spark.streaming.blockInterval' should be a positive value")
     
     // 这个方法比较有意思, RecurringTimer 的功能类似于一个触发器, 传入系统基准时间 clock
     // 和触发时间间隔 blockIntervalMs, 以及触发执行的方法 updateCurrentBuffer 
     // 最后是为这个触发器命名的字符串, 全部赋值好后, 将会以 clock 这个时间为起始时间
     // 每隔 $blockIntervalMs 时间间隔, 定期触发一次 updateCurrentBuffer 这个方法
     // --- 
     // 而仔细看下这个 RecurringTimer 类的话, 会发现其中就是周期性地启动 1 个线程来执行 updateCurrentBuffer 函数的逻辑
     // 而这个线程, 也就是一开始注释信息中提到的, 周期性接收 batch 数据, 然后构建 block 的这个线程了: 
     // "This class starts two threads, one to periodically start a new batch and prepare the previous batch of as a block"
     private val blockIntervalTimer = 
         new RecurringTimer(clock, blockIntervalMs, updateCurrentBuffer, "BlockGenerator")

     // 这个参数加载方式和上述的 block.interval 参数的加载方式一样, 从配置文件中读取队列长度数值
     private val blockQueueSize = conf.getInt("spark.streaming.blockQueueSize", 10)

     // 根据配置项中的队列长度数值来创建对应长度的队列, 队列中存放的元素是 Block 类型的
     private val blockForPushing = new ArrayBlockingQueue[Block](blockQueueSize)

     // 而这个是, 前文提交到的另一个线程, 那个将 block 不断推送给 BlockManager 的线程
     // 线程中执行的方法是 keepPushingBlocks 
     private val blockPushingThread = new Thread { override def run() { keepPushingBlocks() } }

     
     // 这个用 volatile 修饰的 currentBuffer 对它执行的写操作, 是直接落到 CPU 直接访问的 寄存器 空间上的, 而之所以使用 volatile 关键字来修饰该变量
     // 目的是防止,多个线程操作这个对象, 一个线程对该对象执行 update 更新其中数值的时候, 变动发生在内存, 这样对其进行访问的另一个线程
     // 所获取到的这个变量的数值还停留在原先的没更新的数值, 造成这种数据不一致
     // 而有了 @volatile 这个关键字, 对数值的操作会直接更新在 CPU 直接访问的寄存器 空间上的, 占用同一块空间的所有线程都能立即察觉到数值的瞬时变动
     @volatile private var currentBuffer = new ArrayBuffer[Any]
     @volatile private var state = Initialized 

     // Start block generating and pushing threads 
     // 启动 2 个线程, 一个用来周期性触发构建 block, 另一个将本地构建好的 block pushing 到 BlockManager 
     def start():Unit = synchronized {
     	// start 状态转换的入口条件必须是, 状态为 Initialized 才能够进入到 start() 方法内
     	// 否则便会报错抛异常: 当状态为非 Initialized 时, 无法进入 BlockGenerator start 函数
     	if ( state == Initialized) {
     		// 首先成功进入方法后, 最先将状态置位 Active 状态
     		state = Active

     		// 然后, 启动周期性生成 block 触发器线程
     		blockIntervalTimer.start() 
     		// 和推送 block 到 BlockManager 的线程
     		blockPUshingThread.start() 

     		// 打印日志, BlockGenerator 启动了
     		logInfo("Started BlockGenerator")
     	} else {
     		// 否则, 状态不满足无法执行 start 函数
     		throw new SparkException(
     			s"Cannot start BlockGenerator as its not in the Initialized state [state = $state]")
     	}
     }


     // Stop everything in the right order such that all the data added is pushed out correctly.
     // 顺序停掉所有操作, 例如对于, 上游数据流加载, 和 block 的推送操作, 必须按照顺序先停掉上游数据加载这里的操作,
     // 然后, 将最后的数据构建成 block 推送到 BlockManager 之后, 再停止整个的数据推送操作
     // (因为颠倒顺序的话, 会出现数据滞留本地, 没有被推送到 BlockManager 的情况, 造成数据丢失等错误)
     // ---
     // - First, stop adding data to the current buffer . 
     // - 首先, 停到将上游数据不断写入本地缓冲区的操作.
     // ---
     // - Second, stop generating blocks. 
     // - 然后, 停掉构建数据块这样操作.
     // ---
     // - Finally, wait for queue to-be-pushed blocks to be drained. 
     // - 最后, 等待推送线程将所有等待推送的 block 所在的队列中的所有 block 全部推送完毕. 即将滞留在队列中的数据全部推送到 BlockManager 端. 
     def stop():Unit ={
     	// Set the state to stop adding data 
     	synchronized {
     		// stop 入口处判断状态, 非 Active 状态无法进入到 stop 方法中
     		if ( state == Active ) {
     			state = StoppedAddingData 
     		} else {
     			logWarnings(s"Cannot stop BlockGenerator as its not in the Active state [state =$state]")
     		}
     	} 
     	// 在这里我们发现的是, 但凡是对 state 进行判断和变更的时候, 都使用同步代码块将代码包起来, 
     	// 这么做的目的应该是防止多个线程同时执行 stop/ start 方法对其中状态修改生成脏数据状态信息造成状态机状态切换出现问题 

     	// Stop generating blocks and set the state for block pushing thread to start draining the queue 
     	// 停掉构建数据块操作, 然后进行状态切换, 来让数据块推送线程开始推送队列中所有的 Block 到 BlockManager 

        // 打印日志
     	logInfo("Stopping BlockGenerator")

     	// 以非中断的方式将触发器中执行的操作执行完
     	blockIntervalTimer.stop( interruptTimer = false )

        // 将状态置位 StoppedAll 
     	synchornized { state = StoppedAll }

     	// 打印日志, 将 BlockGenerator 停止服务的日志打印出来, 这个 stop 方法中进行了 2 此状态切换
     	logInfo("Stopped BlockGenerator")
     }  

     // Push a single data item into the buffer 
     // addData 方法用于将单独的一个数据项写入到缓冲区中
     def addData(data:Any):Unit = {
     	if (state == Active) {
     		// 在执行 addData 方法之前, 首先需要判断一下当前状态机所处的状态, 只有状态为 Active 状态时, 才能执行下述的操作
     		// 这个 waitToPush() 方法是 BlockGenerator 所继承的 RateLimiter 基类中所提供的方法,
     		// 而这个 RateLimiter 则是反压/backpressure/限流 功能最终要的实现,限流的功能所使用的底层类库是 google 库中的 RateLimiter 这个类中提供的功能
     		// 而其实你也可以理解为 spark 中的 RateLimiter 这个类实际上是封装了 google RateLimiter 这个类, 负责对 google RateLimiter 的接口进行封装调用
     		// 然后对系统中传入的参数进行适配转换, 传入至 RateLimiter 中来运行
     		// -----
     		// 关于 RateLimiter.waitToPush 这个函数而言, 它的功能源码中也有相关的注释我认为很重要, 在这里也一并记录一下
     		/**
               waitToPush 
               * (RateLimiter)Provides waitToPush() method to limit the rate at which receivers consume data. 
               * (RateLimiter) 中提供的 waitToPush 这个方法的主要作用便是对消费数据进行限流
               * ---
               * waitToPush method will block the thread if too many messages have been pushed too quickly, and only return when a new message has been pushed. 
               * 如果上游消息推送速率太快的话, waitToPush 这个方法将会把接收消息的线程进行阻塞掉, 当新消息到达时仅仅返回作为响应, 而并非接收处理该消息. 
               * --- 
               * It assumes that only one message is pushed at a time.
               * 通过 waitToPush 方法能确保每次只推送 1 条消息. 
               * --- 
               * The spark configuration spark.streaming.receiver.maxRate gives the maximum number of messages per seconds that receiver will accept. 
               * spark 的配置项中的 spark.streaming.receiver.maxRate 这个选项给定了每秒 spark 作为数据接收端最多能够处理的消息条数上限.
     		*/ 
     		waitToPush() // 调用该方法, 根据系统当前处理吞吐量来更新接收消息的速率
     		synchronized {
     			if ( state == Active ) {
     				// 串行方式再次判断状态机当前所处的状态是否为 Active, 这里再次判断是为了确保线程在执行外 waitToPush 方法之后, 引 waitToPush 这个方法
     				// 并没有控制串行之后, 防止其他线程再此期间更新了状态机的状态, 而至于为何 waitToPush 这个方法没有串行执行, 是为了增大线程并发的粒度
                    // 确认状态无误之后, 将参数传入的 data 数据信息, 追加到缓存空间中
     				currentBuffer += data 
     			} else {
     				// 如果状态非 Active 还要执行 if 分支中追加数据的逻辑的话会抛出 SparkException 的异常信息
     				// 异常到达这里有可能是, 线程一开始判断当前状态机状态处于 Active 的状态, 但是执行 waitToPush 之后, 状态机的状态
     				// 发生了切换, 1 中是状态机因为其他 event 执行的操作切换到了 stop 状态, 也有可能是切换到 stop 之后, 再次启动到了 start 刚刚启动状态
     				// 这两种状态均无法读取数据, 所以到达这里抛出异常信息 
     				throw new SparkException (
     					"Cannot add data as BlockGenerator has not been started or has been stopped")
     			}
     		}
     	} else{
          throw new SparkException (
          	// 代码到达这里是, 一开始执行 addData 方法的时候状态便不是 Active 状态了, 所以外层 if 内部的逻辑没有执行, 直接执行到这里抛出异常
          	"Cannot add data as BlockGenerator has been started or has been stopped")
     	} 
     }

    /**
     Push a single data item into the buffer. After buffering the data, the 
     `BlockGeneratorListener.onAddData` callback will be called. 
     //---
     在这个方法中, 我们推送单独的一条数据项到缓冲区中, 在将单条数据项写入缓冲区中之后, 
     才会触发对 BlockGeneratorListener 中 onAddData 的回调方法的调用. 
    */
    def addDataWithCallback(data:Any, metadata:Any):Unit = {
        if (state == Active) {
        	waitToPush() // 虽然 RateLimiter 关于这个方法的描述很明白, 但是这里并不太清楚调用这个方法具体有什么作用, 是阻塞掉对上游数据的拉取这个操作,
        	// 等到处理完 data 参数之后才继续读取数据么？  
        	synchronized {
        		if ( state == Active ) {
        			// 我们将缓冲区中追加此次读取的 1 个数据项
        			currentBuffer += data 
        			// 然后, 才触发 listener 中的回调函数, 将此次追加的 1 条数据项和当前的 metadata 元数据信息传入到回调函数中
        			listener.onAddData(data, metadata)
        		} else {
                    // 代码执行到这里是由于, 一开始线程进入 addDataWithCallback 方法的时候, 状态为 Active 
                    // 但是执行完 waitToPush 方法之后, 状态机的状态发生切换不再是 Active 状态了, 所以继续执行剩下的逻辑
                    // 会导致状态机状态切换出现问题, 所以会执行到这里抛出异常, 打印提示新
                    throw new SparkException("Cannot add data as BlockGenerator has not been started or has been stopped")
        		}
        	}
        } else {
        	// 状态非 Active 又想执行 addDataWithCallback 函数会导致状态机切换出现问题, 所以会执行到这里抛出异常, 打印提示信息
        	throw new SparkException(
        		"Cannot add data as BlockGenerator has not been started or has been stopped. ")
        }
    }

    /**
     Push multiple data items into the buffer. After buffering the data, the BlockGeneratorListener.onAddData callback will be called. 
     Note that all the data items are atomically added to the buffer, and are hence guaranteed to be present in a single block. 
     如下的这个 `addMultipleDataWithCallback` 方法是用来一次性将多个数据项推送到缓冲区中的. 
     在方法中逐个遍历数据项,将所有的数据项逐个追加到临时开辟的缓存空间中, 然后统一触发调用一次 listener 的回调方法将开辟的缓存空间数据一次性提交给缓存空间
     // --- 
     Note that all the data items are atomically added to the buffer, and are hence guaranteed to be present in a single block.
     需要注意的是, 所有的数据项的追加操作, 以及回调方法的触发操作均是原子粒度的, 而且 addMultipleDataWithCallback 这个函数中所遍历操作的所有
     数据项均属于同一个 block 中额数据(其实这里仔细想一想, 这个方法调用中的 dataIterator 中的每个数据项可以将其看作是每个 batch 到达的数据,
     而在前文注释中也有提到, 多个 batch 到达的数据, 汇聚成 1 个 block, 同时, 控制 1 个 block 中 batch 个数是间接地由 rate.limiter 这个参数来控制的. )
    */
    def addMultipleDataWithCallback( dataIterator:Iterator[Any], metadata:Any):Unit = {
        if ( state == Active ) {
        	// Unroll iterator into a temp buffer, and wait for pushing in the process 
        	// 逐个遍历 dataIterator 中的数据项, 然后将其逐个追加到缓冲区中

        	// 首先开辟一块数据缓冲区, 由 tempBuffer 作为引用指向这块空间
        	val tempBuffer = new ArrayBuffer[Any]
        	dataIterator.foreach { data => 
                waitToPush() 
                // 到这里再次看到 waitToPush 这个方法, 我觉得这个方法是通过限流的方法, 每次仅从上游数据流中读取 1 个数据项
                // 因为在这里, 我们刚好遍历 1 个数据项, 将数据项给消费了, 而调用 waitToPush() 的频度和消费的频度一致
                // 所以很有可能是, 我们维护一块缓冲空间, 空间大小一定, 我们没消费处理一条数据, 便在调用这个 waitToPush 函数从上游拉取一条数据

                tempBuffer += data 
                // 这里我们将数据项追加到缓存空间中 
        	} // 结束数据项的迭代遍历, 此时数据项已经从多条汇聚到缓存空间中的一个数据块, 即, tempBuffer 

        	synchronized {
        		if (state == Active) {
        			// 在这里再次检查当前状态机所处的状态是否为 Active, 
        			// 如果状态没有问题, 就将累加得到的临时缓冲区的 tempBuffer 一次性追加到 currentBuffer 缓冲空间中 
        			// 然后将此次累加的 tempBuffer 和包含当前信息的 metadata 传入至 listener 中的 onAddData 回调函数中
        			currentBuffer += tempBuffer 
        			listener.onAddData(tempBuffer, metadata)
        		} else {
        			// 如果再次检查状态发生变动, 为保证状态机状态切换正常流转, 在这里抛异常打印提示信息
        			throw new SparkException(
        				"Cannot add data as BlockGenerator has not been started or has been stopped")
        		}
        	}
        } else {
        	// 一开始检查状态机状态信息不满足执行方法所需状态的话, 直接抛异常打印提示信息
        	throw new SparkException (
        		"Cannot add data as BlockGenerator has not been started or has been stopped")
        }
    }

    def isActive():Boolean = state == Active 

    def isStopped():Boolean = state == StoppedAll 

    // Change the buffer to which single records are added to 
    // 这个 updateCurrentBuffer 方法是将成员变量 currentBuffer 所指向的原有内存空间
    // 赋值给一个临时的引用对象指向, 然后将该临时引用对象指向的内存空间构建 Block 对象
    // 然后数据便从缓冲区中转到了 Block 对象中, 最终会被发往 BlockManager 由 BlockManager 来管理这块 Block 中的空间数据
    // 而 currentBuffer 再将其原有空间让临时引用对象指向保证这块空间不会因为没有引用指向它而被 GC 回收,
    // currentBuffer 会指向新开辟的一块数据空间, 待到 Block 构建好托管至 BlockManager 之后,
    // 方法退出, 临时的引用对象被销毁, 原有的数据空间便没有存活的引用来指向它, 这个时候如果发生垃圾回收的话
    // 这块空间会被 GC 探测到, 进而会被释放与回收
    private def updateCurrentBuffer(time:Long):Unit = {
    	try {
    		var newBlock:Block = null 
    		synchronized {
    			if ( currentBuffer.nonEmpty ) {
    				// 在这里我们使用 newBlockBuffer 和 currentBuffer 指向相同的内存缓冲空间
    				val newBlockBuffer = currentBuffer 
    				// 然后转移类成员变量指向一块新开辟的内存缓冲空间
    				currentBuffer = new ArrayBuffer[Any]
    				// 然后, 我们将 BlockGenerator 构造函数传入的 receiverId 和 当前时间 - block 生成的时间间隔 这两个参数
    				// 来构建 StreamBlockId 对象实例
    				val blockId = StreamBlockId(receiverId, time - blockIntervalMs)
    				// 再我们构建好 blockId 之后, 我们调用 listener 中的 onGenerateBlock , 将构建 block 的事件传递给 listener 
    				// 连同要构建的 block 的 blockId 一并通过回调函数进行回传, 如果有必要的话, 在回调函数的逻辑中, 我们会将
    				// 相关的 metadata 及 blockId 信息同步更新到某个缓存中等等 
    				listener.onGenerateBlock(blockId)

    				// 在每个执行逻辑中, 回调函数及回调函数下述的逻辑都是串行的, 所以我们执行回调函数完毕之后, 才会继续执行构建 Block 对象的逻辑 
    				newBlock = new Block(blockId, newBlockBuffer)
    			}
    		}

    		if (newBlock != null) {
    			// 在上面的操作中, 我们创建了 Block 对象, 在下面的环节中, 主要就是要把这个 Block 对象推送到 BlockManager 中, 让 BlockManager 来管理新构建的 Block 对象
    			// 而推送的过程是 1 个线程负责推送, + 1 个队列进行缓存来实现的(其实和线程池差不多), 每次线程从队列中获取队头的 Block 元素
    			// 然后将其发送给 BlockManager 
    			// 在这里, 如果队列已满, 那么将新创建的 Block 加入到队列中这一步操作也会阻塞住, 阻塞的同时状态机会停在当前的状态上, 也能够实现不从上游数据中拉取数据
    			// 这样避免了数据不断从上游读取而造成的本地滞留数据量太大. 
    			// --- 
    			// 总之, 在这里, 只需要了解, Block 在发送之前, 是先进入到 Block 队列中进行排队的, 然后按照 且队列控制的 FIFO 的顺序被逐个处理发送出去的就 ok 了
    			blocksForPushing.put(newBlock) 
    			// put is blocking when queue is full 
    		}
    	} catch {
    		case ie: InterruptedException => 
    		    logInfo("Block updating timer thread was interrupted")
    		case e: Exception => 
    		     // 在这里的 reportError 方法底层也会调用 listener 中的 onError 这个回调函数
    		     // 其实 listener 看到这里, 我大概了解回调函数的作用了
    		     // 就是在状态机在不同状态间切换的时候, 我们想为其增加一些个性化或者是定制化的处理
    		     // 比如在某个状态切换点, 我们需要切换期间的上下文信息, 比如就说数据流的 metadata 信息吧
    		     // 我们需要获取, 然后将这个 metadata 进行分析, 将分析结果写到外存,
    		     // 直接加到状态机中会让状态机中的逻辑代码变得十分冗余, 而且每次定制/个性化处理的方式不同, 需要频繁修改
    		     // 状态机的代码, 这么做并不稳妥, 
    		     // 所以, 在这里设计者, 通过 listener 里面定制了相关的接口, 
    		     // 以及在状态机中, 特定的状态流转和切换的点上, 通过 listener 暴露一些对外的开口, 
    		     // 这样用户便可通过暴露的这些开口, 获取状态机中内部的上下文信息, 然后通过实现 listener 中定制的接口方法
    		     // 来根据自己的需要来完成对暴露接口所获取的上下文信息的处理逻辑
    		     // 这大概也是不破坏原有逻辑, 可以动态增加方法逻辑的很好的设计方法
    		     // ---- 
    		     // 这个地方有一点点类似于 spring 中的面向切面的编程(AOP), 不过 AOP 中的切面是在 class 上增加的处理逻辑
    		     // 而 listener + event + callback 这种还是直接在程序上增加的处理逻辑 
    		    reportError("Error in block updating thread", e )
    	}
    }

    // Keep pushing blocks to the BlockManager 
    // 是的, 没错, 在前面注释信息中, 我们不是提到了 2 个线程么, 一个负责周期构建 Block,
    // 另一个负责从队列中取 Block 然后将 Block 推送到 BlockManager 
    // 而这个 keepPushingBlocks 方法就是第2个线程中底层调用的方法
    private def keepPushingBlocks(){
    	// 打日志标识, 开启 Block 推送进程
        logInfo("Started block pushing thread")
        
        // 这个方法被创建出来供在 keepPushingBlocks 方法范围内来使用, 
        // 用于快速检测当前状态机所处的状态是否处于 停止构建 Block 的状态
        def areBlocksBeingGenerated:Boolean = synchronized {
            state != StoppedGeneratingBlocks 
        }

        try {
        	// While blocks are being generated, keep polling for to-be-pushed blocks and push them. 
        	// 在 block 不断被构建的期间, 持续地从 Block 队列中 FIFO 拉取 Block 并将其发送到 BlockManager 
        	while (areBlocksBeingGenerated) {
        		// 检测, 只要状态不是 'StoppedGeneratingBlocks' 就继续维持这个循环进行
        		Option(blocksForPushing.poll(10, TimeUnit.MILLISECONDS)) match {
        			// 在这里, 值得注意一下 ArrayBlockingQueue 中的 poll 这个函数
        			// 这个函数首个参数是时间长度, 第二个参数是时间单位
        			// 大意是, 在等待的 10 毫秒时间内, 尝试从队列中阻塞式地拉取队头元素, 
        			// 如果队列非空, 直接拉取队头元素即可, 如果一开始队列为空, 并在所等待的指定阻塞时间内 10 ms 队列还是空的,
        			// 则不会继续阻塞, 直接返回 null, 而下面的 两个 case 也是分别匹配了
        			// 返回为 null 和 返回正常队头元素 Block 的不同处理逻辑, 
        			// --->>> 返回为 null 的时不错任何处理
        			// --->>> 返回队头元素 Block 的时候调用成员方法 pushBlock, 间接地通过 listener 中的回调函数来完成数据向 BlockManager 的推送
        			case Some(block) => pushBlock(block)
        			case None => 
        		}
        	}

        	// At this point, state is StoppedGeneratingBlock. So drain the queue of to-be-pushed blocks. 
        	// 如果程序代码执行到了这里, 就说明当前状态机所处于的状态为 StoppedGeneratingBlocks 这个状态了
        	// 这个状态说明, 上游已经不再构建 Block 了, 滞留在本地的阻塞队列中的元素便是所有 Block 了
        	// 我们无需顾虑处理将 Block 加到队列中这里的操作, 只需要将队列中的所有元素逐个推送到 BlockManager 即可
        	// ---- 啰嗦啰嗦
        	// 虽然我对 SparkListener/ListenerBus 这里的代码还没有开始仔细看, 但是这里的处理的逻辑, 真的是与StreamingContext 退出之前
        	// 不再接收新到达 job 而是将队列中滞留的所有 job 逐步 submit 提交的处理逻辑好相似!!!
        	// ---- 结束啰嗦
             
            // 打印日志信息, 标识现在开始遍历队列, 然后将队列中所有的元素进行数据推送
        	logInfo("Pushing out the last " + blocksForPushing.size() + " blocks")
        
            while ( !blocksForPushing.isEmpty ) {
            	// 这个循环就是从头开始逐个访问队列中的元素
            	val block = blocksForPushing.take() // 获取队列中的首个元素

            	// 打个 DEBUG 级别的日志, 标明现在就开始推送最后的 block 了
            	logDebug(s"Pushing block $block")

            	pushBlock(block) // 就是调用下面定义的这个 pushBlock 函数, 底层调用的是 listener 中的 onPushBlock 这个回调函数

            	// 最后打日志标识一下, 现在队列中还有多少个 Block 没有被推送出去 
            	logInfo("Blocks left to push " + blockForPushing.size())
            } catch {
            	// 剩下的这两个就是当上述操作抛出异常的时候, 到这里统一处理记录下
               case ie:InterruptedException => 
                   logInfo("Block pushing thread was interrupted ")
               case e: Exception => 
                   reportError("Error in block pushing thread", e)
            }
        }
    }

    private def reportError(message:String, t:Throwable) {
    	logError(message, t)
    	listener.onError(message, t)
    }

    // 这个方法会调用定义在 listener 中的 onPushBlock 回调函数
    private def pushBlock(block:Block) { 
        listener.onPushBlock(block.id, block.buffer)
        logInfo("Pushed block " + block.id)
    }
}

// ---- 阅读之前写的 ---- 

// 在阅读完作者自己实现的加持各种可靠, 无丢失 buff 的 ReliableKafkaReceiver.scala 之后, 发现这个类中很多方法
// 都是各种处理 Block 和 Seq[topic.partition-offset] 二者的关联映射关系, 
// 我在这里其实并不是很清楚为什么每次在构建映射关系的时候,为何要将缓存 key:topic.parition, value:offset 关系的 hash map 给清空, 
// 也就是下面注释的这段代码中最后的 clear 方法调用清空 hash map 
/**

// Remember the current offset for each topic and partition. 
// This is called when a block is generated .
private def rememberBlockOffsets(blockId:StreamBlockId):Unit = {
	// Get a snapshot of current offset map and store with related block id. 
	val offsetSnapshot = topicPartitionOffsetMap.toMap 
	blockOffsetMap.put(blockId, offsetSnapshot)
	topicPartitionOffsetMap.clear() // why clean the contents in hash map here ? 
}
*/
// 虽然我觉得是, 上游 Receiver 基类在构建 block 的时, 是按照整个系统中的 block.interval 构建周期, 周期性地读取数据并构建,
// 每次存放到 hash map 中的 topic,partition - offset 仅仅是一个 block.interval 时间周期的数据信息, 因为这个 block.interval 完成, 数据入 block, 
// 建立这个 block 的 block id 与 多个 topic.partition 的 offset 之后, 清空 topic.partition offset 的 hash map 缓存, 
// 好等待下个 block.interval 新数据到来好记录新到达的多个 batch 中从 kafka 拉取的 topic.partition - offset 的数据关系对 ? 
// 后来想了一下, 是因为我对调用 rememberBlockOffsets 函数的 BlockGeneratorListener 中定义的回调函数: onGenerateBlock 
// 这个回调函数所处理的 Event 到达的时机不理解, 导致我不清楚 rememberBlockOffsets 函数被执行的时间背景是什么样的, 
// 进而导致了我对其中缓存 hash map 中数据 clear 清空方法的不了解, 所以这里决定阅读批注一下 BlockGenerator.scala 这一份源码
// 以及, 如果能把这份代码中处理数据的流程使用图示的方法表述出来能够进一步了解整个模块的运行原理. 

