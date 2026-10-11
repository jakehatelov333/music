<script setup lang="ts">
import DefaultLayout from '@/layout/index.vue'
import { useRoute } from 'vue-router'

const route = useRoute()
</script>

<template>
  <router-view v-if="route.meta.standalone" />
  <DefaultLayout v-else />
</template>
