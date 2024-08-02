package org.birkir.carplay

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.OnBackPressedCallback
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.ScreenManager
import androidx.car.app.SessionInfo
import androidx.car.app.model.Alert
import androidx.car.app.model.AlertCallback
import androidx.car.app.model.CarText
import androidx.car.app.model.Distance
import androidx.car.app.model.Template
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Callback
import com.facebook.react.bridge.LifecycleEventListener
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.WritableNativeMap
import com.facebook.react.module.annotations.ReactModule
import com.facebook.react.modules.debug.DevSettingsModule
import org.birkir.carplay.parser.Parser
import org.birkir.carplay.parser.TemplateParser
import org.birkir.carplay.screens.CarScreen
import org.birkir.carplay.screens.CarScreenContext
import org.birkir.carplay.utils.EventEmitter
import java.util.WeakHashMap


@ReactModule(name = CarPlayModule.NAME)
class CarPlayModule internal constructor(private val reactContext: ReactApplicationContext) :
  ReactContextBaseJavaModule(reactContext) {
  private var isInitialized = false
  private lateinit var carContext: CarContext
  private val pendingOperations = mutableListOf<() -> Unit>()  
  private lateinit var parser: Parser;

  private var currentCarScreen: CarScreen? = null
  private var screenManager: ScreenManager? = null
  private val carScreens: WeakHashMap<String, CarScreen> = WeakHashMap()
  private val carTemplates: WeakHashMap<String, ReadableMap> = WeakHashMap()
  private val carScreenContexts: WeakHashMap<CarScreen, CarScreenContext> =
    WeakHashMap()
  private val handler: Handler = Handler(Looper.getMainLooper())


  // Global event emitter (no templateId's)
  private var eventEmitter: EventEmitter? = null

  init {
    reactContext.addLifecycleEventListener(object : LifecycleEventListener {
      override fun onHostResume() {
        eventEmitter = EventEmitter(reactContext)
        reactContext.getNativeModule(DevSettingsModule::class.java)
          ?.addMenuItem("Reload Android Auto")
      }

      override fun onHostPause() {}
      override fun onHostDestroy() {}
    })
  }

  override fun getName(): String {
    return NAME
  }

  fun setCarContext(carContext: CarContext, currentCarScreen: CarScreen) {
    parser = Parser(carContext, CarScreenContext("", eventEmitter!!, carScreens));
    this.carContext = carContext
    this.currentCarScreen = currentCarScreen
    screenManager = currentCarScreen.screenManager
    carScreens["root"] = this.currentCarScreen
    carContext.onBackPressedDispatcher.addCallback(object : OnBackPressedCallback(true) {
      override fun handleOnBackPressed() {
        eventEmitter?.backButtonPressed(screenManager?.top?.marker)
      }
    })
    eventEmitter?.didConnect()
    isInitialized = true
    Log.d(TAG, "CarPlay context initialized")
    processPendingOperations()
  }

  private fun processPendingOperations() {
    handler.post {
      pendingOperations.forEach { it.invoke() }
      pendingOperations.clear()
    }
  }

  private fun executeOrQueue(operation: () -> Unit) {
    if (isInitialized) {
      handler.post(operation)
    } else {
      pendingOperations.add(operation)
      Log.d(TAG, "Operation queued. Waiting for CarPlay initialization.")
    }
  }

  private fun parseTemplate(
    config: ReadableMap,
    carScreenContext: CarScreenContext
  ): Template {
    val factory = TemplateParser(carContext, carScreenContext)
    return factory.parse(config)
  }

  @ReactMethod
  fun checkForConnection() {
    eventEmitter?.didConnect()
  }

 @ReactMethod
  fun createTemplate(templateId: String, config: ReadableMap, callback: Callback?) {
    executeOrQueue {
      Log.d(TAG, "Creating template $templateId")
      carTemplates[templateId] = config
      createScreen(templateId) { screen ->
        if (screen != null) {
          callback?.invoke()
        } else {
          val args = Arguments.createMap()
          args.putString("error", "Failed to create screen for template '$templateId'")
          callback?.invoke(args)
        }
      }
    }
  }

  @ReactMethod
  fun updateTemplate(templateId: String, config: ReadableMap) {
    executeOrQueue {
      carTemplates[templateId] = config;
      val screen = carScreens[name]
      if (screen != null) {
        val carScreenContext = carScreenContexts[screen];
        if (carScreenContext != null) {
          val template = parseTemplate(config, carScreenContext);
          screen.setTemplate(template, templateId, config);
          screen.invalidate()
        }
      }
    }
  }

  @ReactMethod
  fun setRootTemplate(templateId: String, animated: Boolean?) {
      executeOrQueue {
          Log.d(TAG, "set Root Template for $templateId")
          getScreen(templateId) { screen ->
              if (screen != null) {
                  currentCarScreen = screen
                  screenManager?.popToRoot()
                  screenManager?.push(screen)
              } else {
                  Log.e(TAG, "Failed to set root template $templateId: screen creation failed")
              }
          }
      }
  }

  @ReactMethod
  fun pushTemplate(templateId: String, animated: Boolean?) {
      executeOrQueue {
          getScreen(templateId) { screen ->
              if (screen != null) {
                  currentCarScreen = screen
                  screenManager?.push(screen)
              } else {
                  Log.e(TAG, "Failed to push template $templateId: screen creation failed")
              }
          }
      }
  }

  @ReactMethod
  fun popToTemplate(templateId: String, animated: Boolean?) {
    executeOrQueue {
      screenManager?.popTo(templateId);
    }
  }

  @ReactMethod
  fun popTemplate(animated: Boolean?) {
    executeOrQueue {
      screenManager!!.pop()
      removeScreen(currentCarScreen)
      currentCarScreen = screenManager!!.top as CarScreen
      currentCarScreen?.invalidate()
    }
  }

  @ReactMethod
  fun presentTemplate(templateId: String?, animated: Boolean?) {
    // void
  }

  @ReactMethod
  fun dismissTemplate(templateId: String?, animated: Boolean?) {
    // void
  }

  // pragma: Android Auto only stuff

  @ReactMethod
  fun toast(text: String, duration: Int) {
    if (!::carContext.isInitialized) {
       Log.e(TAG, "carContext is not initialized. Cannot show toast.")
       return
    }
    CarToast.makeText(carContext, text, duration).show()
  }

  @ReactMethod
  fun alert(props: ReadableMap) {
    executeOrQueue {
      val id = props.getInt("id");
      val title = parser.parseCarText(props.getString("title")!!, props);
      val duration = props.getInt("duration").toLong();
      val alert = Alert.Builder(id, title, duration).apply {
        setCallback(object : AlertCallback {
          override fun onCancel(reason: Int) {
            val reasonString = when (reason) {
              AlertCallback.REASON_TIMEOUT -> "timeout"
              AlertCallback.REASON_USER_ACTION -> "userAction"
              AlertCallback.REASON_NOT_SUPPORTED -> "notSupported"
              else -> "unknown"
            }
            eventEmitter?.alertActionPressed("cancel", reasonString);
          }
          override fun onDismiss() {
            eventEmitter?.alertActionPressed("dismiss" );
          }
        })
        props.getString("subtitle")?.let { setSubtitle(parser.parseCarText(it, props)) }
        props.getMap("icon")?.let { setIcon(parser.parseCarIcon(it)) }
        props.getArray("actions")?.let {
          for (i in 0 until it.size()) {
            addAction(parser.parseAction(it.getMap(i)));
          }
        }
      }.build()
      carContext.getCarService(AppManager::class.java).showAlert(alert)
    }
  }

  @ReactMethod
  fun dismissAlert(alertId: Int) {
    carContext.getCarService(AppManager::class.java).dismissAlert(alertId)
  }

 @ReactMethod
 fun invalidate(templateId: String) {
      executeOrQueue {
          getScreen(templateId) { screen ->
              if (screen != null && screen === screenManager?.top) {
                  Log.d(TAG, "Invalidated screen $templateId")
                  screen.invalidate()
              }
          }
      }
  }

  @ReactMethod
  fun reload() {
    val intent = Intent("org.birkir.carplay.APP_RELOAD")
    reactContext.sendBroadcast(intent)
  }

  @ReactMethod
  fun getHostInfo(promise: Promise) {
    return promise.resolve(Arguments.createMap().apply {
      carContext.hostInfo?.packageName?.let { putString("packageName", it) }
      carContext.hostInfo?.uid?.let { putInt("uid", it) }
    });
  }

  // Others

  @ReactMethod
  fun addListener(eventName: String) {
    Log.d(TAG, "listener added $eventName")
  }

  @ReactMethod
  fun removeListeners(count: Int) {
    Log.d(TAG, "remove listeners $count")
  }

  private fun createCarScreenContext(screen: CarScreen): CarScreenContext {
    val templateId = screen.marker!!
    return CarScreenContext(templateId, EventEmitter(reactContext, templateId), carScreens)
  }

   private fun createScreen(templateId: String, callback: ((CarScreen?) -> Unit)? = null) {
    executeOrQueue {
      try {
        val config = carTemplates[templateId]
        if (config == null) {
          Log.e(TAG, "No template config found for $templateId")
          callback?.invoke(null)
          return@executeOrQueue
        }

        val screen = CarScreen(carContext)
        screen.marker = templateId

        val carScreenContext = createCarScreenContext(screen)
        carScreenContexts[screen] = carScreenContext

        val template = parseTemplate(config, carScreenContext)
        screen.setTemplate(template, templateId, config)
        carScreens[templateId] = screen

        Log.d(TAG, "Screen created for template $templateId")
        callback?.invoke(screen)
      } catch (e: Exception) {
        Log.e(TAG, "Error creating screen for template $templateId", e)
        callback?.invoke(null)
      }
    }
  }

 private fun getScreen(name: String, callback: (CarScreen?) -> Unit) {
    val existingScreen = carScreens[name]
    if (existingScreen != null) {
        callback(existingScreen)
    } else {
        createScreen(name, callback)
    }
}

  private fun removeScreen(screen: CarScreen?) {
    val params = WritableNativeMap()
    params.putString("screen", screen!!.marker)
    carScreens.values.remove(screen)
  }

  companion object {
    const val NAME = "RNCarPlay"
    const val TAG = "CarPlay"
  }
}
