/** 每个请求分配一个代次；上下文切换后，已被取代请求的结果和回调一律忽略。 */
export function createRequestGate() {
  let current = 0;
  return {
    next: () => ++current,
    invalidate: () => {
      current++;
    },
    isCurrent: (generation: number) => generation === current,
  };
}
