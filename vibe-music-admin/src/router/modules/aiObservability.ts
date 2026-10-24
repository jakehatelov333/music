export default {
  path: "/ai-observability",
  redirect: "/ai-observability/overview",
  meta: {
    icon: "ri:line-chart-line",
    title: "AI 可观测性",
    rank: 1.5
  },
  children: [
    {
      path: "/ai-observability/overview",
      name: "AiObservabilityOverview",
      component: () => import("@/views/ai-observability/overview/index.vue"),
      meta: {
        title: "用量总览"
      }
    },
    {
      path: "/ai-observability/traces",
      name: "AiObservabilityTraces",
      component: () => import("@/views/ai-observability/traces/index.vue"),
      meta: {
        title: "Trace 检索"
      }
    },
    {
      path: "/ai-observability/traces/:traceId",
      name: "AiObservabilityTraceDetail",
      component: () => import("@/views/ai-observability/traces/detail.vue"),
      meta: {
        title: "Trace 详情",
        // 详情页从列表进入，不单独占一个左侧菜单项。
        showLink: false
      }
    }
  ]
} satisfies RouteConfigsTable;
