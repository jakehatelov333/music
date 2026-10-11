import { reactive } from "vue";
import type { FormRules } from "element-plus";

export const formRules = reactive(<FormRules>{
  albumName: [
    {
      required: true,
      message: "专辑名为必填项",
      trigger: "blur"
    }
  ]
});
