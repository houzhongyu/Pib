package com.houxinzhe.pib

import android.app.Application
import com.houxinzhe.pib.agent.Agent
import com.houxinzhe.pib.agent.TaskStore
import com.houxinzhe.pib.agent.AppToolContext
import com.houxinzhe.pib.agent.ToolRegistry
import com.houxinzhe.pib.data.ButlerStore
import com.houxinzhe.pib.data.ThreadStore
import com.houxinzhe.pib.data.Settings
import com.houxinzhe.pib.llm.LlmClient
import com.houxinzhe.pib.llm.LlmConfig
import com.houxinzhe.pib.tools.AlarmSetTool
import com.houxinzhe.pib.tools.AmapClient
import com.houxinzhe.pib.tools.AppHandoffTool
import com.houxinzhe.pib.tools.BridgeConfig
import com.houxinzhe.pib.tools.BridgeExecTool
import com.houxinzhe.pib.tools.BridgeStatusTool
import com.houxinzhe.pib.tools.CalendarAddTool
import com.houxinzhe.pib.tools.CalendarListTool
import com.houxinzhe.pib.tools.DshDelegateTool
import com.houxinzhe.pib.tools.DshStatusTool
import com.houxinzhe.pib.tools.ParcelAddTool
import com.houxinzhe.pib.tools.ParcelDoneTool
import com.houxinzhe.pib.tools.ParcelListTool
import com.houxinzhe.pib.tools.PlaceSearchTool
import com.houxinzhe.pib.tools.RemoteBridgeClient
import com.houxinzhe.pib.tools.RoutePlanTool
import com.houxinzhe.pib.tools.ScreenActTool
import com.houxinzhe.pib.tools.ScreenReadTool
import com.houxinzhe.pib.tools.TimerSetTool
import com.houxinzhe.pib.tools.TripAddTool
import com.houxinzhe.pib.tools.TripListTool
import com.houxinzhe.pib.tools.WeatherTool
import java.io.File

/** Manual DI container. */
class PibApp : Application() {
    lateinit var settings: Settings; private set
    lateinit var threads: ThreadStore; private set
    lateinit var tasks: TaskStore; private set
    lateinit var butler: ButlerStore; private set
    lateinit var bridge: RemoteBridgeClient; private set
    lateinit var registry: ToolRegistry; private set
    lateinit var agent: Agent; private set

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        threads = ThreadStore(File(filesDir, "threads"))
        tasks = TaskStore(File(filesDir, "tasks/ledger.json"))
        butler = ButlerStore(File(filesDir, "butler/items.json"))
        val amap = AmapClient(key = { settings.amapKey })
        bridge = RemoteBridgeClient(
            config = {
                BridgeConfig(
                    enabled = settings.bridgeEnabled,
                    primaryUrl = settings.bridgeUrl,
                    fallbackUrl = settings.bridgeFallbackUrl,
                    token = settings.bridgeToken,
                    defaultCwd = settings.bridgeDefaultCwd,
                )
            }
        )
        registry = ToolRegistry(
            listOf(
                AlarmSetTool(), TimerSetTool(),
                CalendarAddTool(), CalendarListTool(),
                PlaceSearchTool(amap), RoutePlanTool(amap), WeatherTool(amap),
                ParcelAddTool(butler), ParcelListTool(butler), ParcelDoneTool(butler),
                TripAddTool(butler), TripListTool(butler),
                AppHandoffTool(),
                ScreenReadTool(), ScreenActTool(),
                BridgeStatusTool(bridge), BridgeExecTool(bridge),
                DshDelegateTool(bridge), DshStatusTool(bridge),
            )
        )
        val llm = LlmClient { LlmConfig(settings.baseUrl, settings.llmKey, settings.model, settings.thinkingTier) }
        agent = Agent(llm, registry, tasks, AppToolContext(this, settings), confirmAllWrites = { settings.confirmAllWrites })
    }
}
