package org.birkir.carplay.parser

import androidx.car.app.CarContext
import androidx.car.app.model.Tab
import androidx.car.app.model.TabContents
import androidx.car.app.model.TabTemplate
import androidx.car.app.model.TabTemplate.TabCallback
import com.facebook.react.bridge.ReadableMap
import org.birkir.carplay.screens.CarScreenContext

class RCTTabTemplate(
  context: CarContext,
  carScreenContext: CarScreenContext
) : RCTTemplate(context, carScreenContext) {
override fun parse(props: ReadableMap): TabTemplate {
    return TabTemplate.Builder(object : TabCallback {
        override fun onTabSelected(tabContentId: String) {
            eventEmitter.didSelectTemplate(tabContentId)
        }
    }).apply {
        setLoading(props.isLoading())
        props.getArray("templates")?.let { templatesArray ->
            for (i in 0 until templatesArray.size()) {
                try {
                    addTab(parseTab(templatesArray.getMap(i)))
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing tab at index $i", e)
                }
            }
            // Apply and select first tab if available
            if (templatesArray.size() > 0) {
                templatesArray.getMap(0).getString("id")?.let { firstTabId ->
                    try {
                        setTabContents(parseTabContents(firstTabId))
                        setActiveTabContentId(firstTabId)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error setting first tab content", e)
                    }
                }
            }
        }
        props.getMap("headerAction")?.let { setHeaderAction(parseAction(it)) }
    }.build()
}

  private fun parseTab(props: ReadableMap): Tab {
    return Tab.Builder().apply {
        props.getString("id")?.let { setContentId(it) }
        // Set a default title if one isn't provided
        setTitle(props.getString("title") ?: "Untitled Tab")
        props.getMap("icon")?.let { setIcon(parseCarIcon(it)) }
    }.build()
}

  private fun parseTabContents(templateId: String): TabContents {
    val screen = carScreenContext.screens[templateId]!!
    return TabContents.Builder(screen.template!!).build()
  }

  companion object {
    const val TAG = "RCTTabTemplate"
  }
}
