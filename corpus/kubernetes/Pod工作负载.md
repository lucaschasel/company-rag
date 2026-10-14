# Pod工作负载

来源：https://kubernetes.io/zh-cn/docs/concepts/workloads/pods/
作者：Kubernetes Authors / Kubernetes website contributors
许可：CC BY 4.0（https://creativecommons.org/licenses/by/4.0/）
获取时间：2026-10-14T11:10:57.776854+08:00
原始版本：51675a591dc393f2fa44b5a72463068236a9932e
整理说明：删除 Hugo 元数据、模板标记及 HTML 注释中的英文对照；正文未由 AI 改写。模板引用示例请查阅官方原文。

---



**Pod** 是可以在 Kubernetes 中创建和管理的、最小的可部署的计算单元。

**Pod**（就像在鲸鱼荚或者豌豆荚中）是一组（一个或多个）
容器；
这些容器共享存储、网络、以及怎样运行这些容器的规约。
Pod 中的内容总是并置（colocated）的并且一同调度，在共享的上下文中运行。
Pod 所建模的是特定于应用的“逻辑主机”，其中包含一个或多个应用容器，
这些容器相对紧密地耦合在一起。
在非云环境中，在相同的物理机或虚拟机上运行的应用类似于在同一逻辑主机上运行的云应用。

除了应用容器，Pod 还可以包含在 Pod 启动期间运行的
Init 容器。
你也可以注入临时性容器来调试正在运行的 Pod。

## 什么是 Pod？   {#what-is-a-pod}

为了运行 Pod，你需要提前在每个节点安装好[容器运行时](/zh-cn/docs/setup/production-environment/container-runtimes/)。

Pod 的共享上下文包括一组 Linux 名字空间、控制组（CGroup）和可能一些其他的隔离方面，
即用来隔离容器的技术。
在 Pod 的上下文中，每个独立的应用可能会进一步实施隔离。

Pod 类似于共享名字空间并共享文件系统卷的一组容器。

Kubernetes 集群中的 Pod 主要有两种用法：

* **运行单个容器的 Pod**。"每个 Pod 一个容器"模型是最常见的 Kubernetes 用例；
  在这种情况下，可以将 Pod 看作单个容器的包装器，并且 Kubernetes 直接管理 Pod，而不是容器。
* **运行多个协同工作的容器的 Pod**。
  Pod 可以封装由紧密耦合且需要共享资源的[多个并置容器](#how-pods-manage-multiple-containers)组成的应用。
  这些位于同一位置的容器构成一个内聚单元。

  
  将多个并置、同管的容器组织到一个 Pod 中是一种相对高级的使用场景。
  只有在一些场景中，容器之间紧密关联时你才应该使用这种模式。

  你不需要运行多个容器来扩展副本（为了弹性或容量）；
  如果你需要多个副本，请参阅[工作负载管理](/zh-cn/docs/concepts/workloads/controllers/)。

## 使用 Pod   {#using-pods}

下面是一个 Pod 示例，它由一个运行镜像 `nginx:1.14.2` 的容器组成。

[引用示例文件：pods/simple-pod.yaml，请查阅官方原文]

要创建上面显示的 Pod，请运行以下命令：

```shell
kubectl apply -f https://k8s.io/examples/pods/simple-pod.yaml
```

Pod 通常不是直接创建的，而是使用工作负载资源创建的。
有关如何将 Pod 用于工作负载资源的更多信息，请参阅[使用 Pod](#working-with-pods)。

### 用于管理 Pod 的工作负载资源   {#workload-resources-for-managing-pods}

通常你不需要直接创建 Pod，甚至单实例 Pod。相反，你会使用诸如
Deployment 或
Job 这类工作负载资源来创建 Pod。
如果 Pod 需要跟踪状态，可以考虑
StatefulSet 资源。

每个 Pod 都旨在运行给定应用程序的单个实例。如果希望横向扩展应用程序
（例如，运行多个实例以提供更多的资源），则应该使用多个 Pod，每个实例使用一个 Pod。
在 Kubernetes 中，这通常被称为**副本（Replication）**。
通常使用一种工作负载资源及其控制器来创建和管理一组 Pod 副本。

参见 [Pod 和控制器](#pods-and-controllers)以了解 Kubernetes
如何使用工作负载资源及其控制器以实现应用的扩缩和自动修复。

Pod 天生地为其成员容器提供了两种共享资源：[网络](#pod-networking)和[存储](#pod-storage)。

## 使用 Pod   {#working-with-pods}

你很少在 Kubernetes 中直接创建一个个的 Pod，甚至是单实例（Singleton）的 Pod。
这是因为 Pod 被设计成了相对临时性的、用后即抛的一次性实体。
当 Pod 由你或者间接地由控制器创建时，
它被调度在集群中的节点上运行。
Pod 会保持在该节点上运行，直到 Pod 结束执行、Pod 对象被删除、Pod 因资源不足而被**驱逐**或者节点失效为止。

重启 Pod 中的容器不应与重启 Pod 混淆。
Pod 不是进程，而是容器运行的环境。
在被删除之前，Pod 会一直存在。

Pod 的名称必须是一个合法的
[DNS 子域](/zh-cn/docs/concepts/overview/working-with-objects/names#dns-subdomain-names)值，
但这可能对 Pod 的主机名产生意外的结果。为获得最佳兼容性，名称应遵循更严格的
[DNS 标签](/zh-cn/docs/concepts/overview/working-with-objects/names#dns-label-names)规则。

### Pod 操作系统   {#pod-os}

你应当将 `.spec.os.name` 字段设置为 `windows` 或 `linux`，
以表明该 Pod 中容器所需的操作系统。目前 Kubernetes
仅支持这两种操作系统。未来，这一列表可能会扩展。

如果 `.spec.os.name` 的值与节点的操作系统不匹配，kubelet 将拒绝运行该 Pod。
在 Kubernetes v 中，`.spec.os.name` 的值对
kube-scheduler
如何选择要运行 Pod 的节点没有影响。在任何有多种操作系统运行节点的集群中，你应该在每个节点上正确设置
[kubernetes.io/os](/zh-cn/docs/reference/labels-annotations-taints/#kubernetes-io-os)
标签，并根据操作系统标签为 Pod 设置 `nodeSelector` 字段。
kube-scheduler 将根据其他标准将你的 Pod 分配到节点，
并且可能会也可能不会成功选择合适的节点位置，其中节点操作系统适合该 Pod 中的容器。
[Pod 安全标准](/zh-cn/docs/concepts/security/pod-security-standards/)也使用这个字段来避免强制执行与该操作系统无关的策略。

### Pod 和控制器    {#pods-and-controllers}

你可以使用工作负载资源来创建和管理多个 Pod。
资源的控制器能够处理副本的管理、上线，并在 Pod 失效时提供自愈能力。
例如，如果一个节点失败，控制器注意到该节点上的 Pod 已经停止工作，
就可以创建替换性的 Pod。调度器会将替身 Pod 调度到一个健康的节点执行。

下面是一些管理一个或者多个 Pod 的工作负载资源的示例：

* Deployment
* StatefulSet
* DaemonSet

### 指定调度组

默认情况下，Kubernetes 会单独调度每一个 Pod。
然而，一些紧密耦合的应用程序需要一组 Pod 能够同时被调度，才能正确运行。

你可以使用[调度组](/zh-cn/docs/concepts/workloads/pods/scheduling-group/)字段
（`spec.schedulingGroup`）将 Pod 链接到 [PodGroup](/zh-cn/docs/concepts/workloads/podgroup-api/)。
这会告诉 `kube-scheduler` 该 `Pod` 属于特定组，
使其能够为整个组应用组级协调放置决策。

### Pod 模板    {#pod-templates}

工作负载资源的控制器通常使用
**Pod 模板（Pod Template）** 来替你创建 Pod 并管理它们。

Pod 模板是包含在工作负载对象中的规范，用来创建 Pod。这类负载资源包括
[Deployment](/zh-cn/docs/concepts/workloads/controllers/deployment/)、
[Job](/zh-cn/docs/concepts/workloads/controllers/job/) 和
[DaemonSet](/zh-cn/docs/concepts/workloads/controllers/daemonset/) 等。

工作负载的控制器会使用负载对象中的 `PodTemplate` 来生成实际的 Pod。
`PodTemplate` 是你用来运行应用时指定的负载资源的目标状态的一部分。

创建 Pod 时，你可以在 Pod 模板中包含 Pod
中运行的容器的[环境变量](/zh-cn/docs/tasks/inject-data-application/define-environment-variable-container/)。

下面的示例是一个简单的 Job 的清单，其中的 `template` 指示启动一个容器。
该 Pod 中的容器会打印一条消息之后暂停。

```yaml
apiVersion: batch/v1
kind: Job
metadata:
  name: hello
spec:
  template:
    # 这里是 Pod 模板
    spec:
      containers:
      - name: hello
        image: busybox:1.28
        command: ['sh', '-c', 'echo "Hello, Kubernetes!" && sleep 3600']
      restartPolicy: OnFailure
    # 以上为 Pod 模板
```

修改 Pod 模板或者切换到新的 Pod 模板都不会对已经存在的 Pod 直接起作用。
如果改变工作负载资源的 Pod 模板，工作负载资源需要使用更新后的模板来创建 Pod，
并使用新创建的 Pod 替换旧的 Pod。

例如，StatefulSet 控制器针对每个 StatefulSet 对象确保运行中的 Pod 与当前的 Pod
模板匹配。如果编辑 StatefulSet 以更改其 Pod 模板，
StatefulSet 将开始基于更新后的模板创建新的 Pod。

每个工作负载资源都实现了自己的规则，用来处理对 Pod 模板的更新。
如果你想了解更多关于 StatefulSet 的具体信息，
请阅读 StatefulSet 基础教程中的[更新策略](/zh-cn/docs/tutorials/stateful-application/basic-stateful-set/#updating-statefulsets)。

在节点上，kubelet 并不直接监测或管理与
Pod 模板相关的细节或模板的更新，这些细节都被抽象出来。
这种抽象和关注点分离简化了整个系统的语义，
并且使得用户可以在不改变现有代码的前提下就能扩展集群的行为。

## Pod 更新与替换   {#pod-update-and-replacement}

正如前面章节所述，当某工作负载的 Pod 模板被改变时，
控制器会基于更新的模板创建新的 Pod 对象而不是对现有 Pod 执行更新或者修补操作。

Kubernetes 并不禁止你直接管理 Pod。对运行中的 Pod 的某些字段执行就地更新操作还是可能的。不过，类似
[`patch`](/docs/reference/generated/kubernetes-api//#patch-pod-v1-core) 和
[`replace`](/docs/reference/generated/kubernetes-api//#replace-pod-v1-core)
这类更新操作有一些限制：

- Pod 的绝大多数元数据都是不可变的。例如，你不可以改变其 `namespace`、`name`、
  `uid` 或者 `creationTimestamp` 字段。
- 如果 `metadata.deletionTimestamp` 已经被设置，则不可以向 `metadata.finalizers`
  列表中添加新的条目。
- Pod 更新不可以改变除 `spec.initContainers[*].image`、`spec.activeDeadlineSeconds`、
  `spec.terminationGracePeriodSeconds`、`spec.tolerations` 或 `spec.schedulingGates` 之外的字段。
  对于 `spec.tolerations`，你只被允许添加新的条目到其中。
- 在更新 `spec.activeDeadlineSeconds` 字段时，以下两种更新操作是被允许的：

  1. 如果该字段尚未设置，可以将其设置为一个正数；
  1. 如果该字段已经设置为一个正数，可以将其设置为一个更小的、非负的整数。

### Pod 子资源

上述更新规则适用于常规的 Pod 更新，但其他 Pod 字段可以通过**子资源**进行更新。

- **调整大小：** `resize` 子资源允许更新容器资源（`spec.containers[*].resources`）。
  更多详情参见[调整容器资源大小](/zh-cn/docs/tasks/configure-pod-container/resize-container-resources/)。
- **临时容器：** `ephemeralContainers` 子资源允许
  临时容器
  被添加到一个 Pod 中。
  更多详情参见[临时容器](/zh-cn/docs/concepts/workloads/pods/ephemeral-containers/)。
- **状态：** `status` 子资源允许更新 Pod 状态。
  这通常仅由 kubelet 和其他系统控制器使用。
- **绑定：** `binding` 子资源允许通过 `Binding` 请求设置 Pod 的 `spec.nodeName`。
  这通常仅由调度器使用。

### Pod 生成

- `metadata.generation` 字段是唯一的。它将由系统自动设置，使得新 Pod 的 `metadata.generation` 为 1，
  并且对 Pod 规约中可变字段的每次更新都会使 `metadata.generation` 增加 1。

- `observedGeneration` 是在 Pod 对象的 `status` 部分中捕获的一个字段。
  kubelet 将设置 `status.observedGeneration` 来追踪当前 Pod 的状态。
  Pod 的 `status.observedGeneration` 将展示报告 Pod 状态时的 Pod 的 `metadata.generation`。

`status.observedGeneration` 字段由 kubelet 管理，外部控制器**不**应修改此字段。

不同的状态字段可能与当前同步循环的 `metadata.generation` 相关联，
或者与前一个同步循环的 `metadata.generation` 相关联。
关键区别在于，`spec` 中的变化是直接体现在 `status` 中，还是作为运行过程的间接结果展示。

#### 直接状态更新

对于那些直接反映分配的 spec 的状态字段，`observedGeneration`
将与当前的 `metadata.generation`（第 N 代）相关联。

此行为适用于：

- **扩缩状态**：资源扩缩操作的状态。
- **分配的资源**：扩缩后分配给 Pod 的资源。
- **临时容器**：当添加一个新的临时容器，并且它处于 `Waiting` 状态时。

#### 间接状态更新

对于那些运行规约的间接结果的状态字段，`observedGeneration`
将与上一个同步循环的 `metadata.generation`（第 N-1 代）相关联。

此行为适用于：

- **容器镜像**：`ContainerStatus.ImageID` 反映的是上一代的镜像，直到新的镜像被拉取并且容器被更新。
- **实际资源**：在扩缩进行中，实际使用的资源仍然属于上一代请求的资源。
- **容器状态**：在扩缩进行中，需要重启策略反映的是上一代的请求。
- **activeDeadlineSeconds** & **terminationGracePeriodSeconds** & **deletionTimestamp**：这些字段对
  Pod 状态的影响是之前观察到的规约的结果。

## 资源共享和通信 {#resource-sharing-and-communication}

Pod 使它的成员容器间能够进行数据共享和通信。

### Pod 中的存储 {#pod-storage}

一个 Pod 可以设置一组共享的存储卷。
Pod 中的所有容器都可以访问该共享卷，从而允许这些容器共享数据。
卷还允许 Pod 中的持久数据保留下来，即使其中的容器需要重新启动。
有关 Kubernetes 如何在 Pod 中实现共享存储并将其提供给 Pod 的更多信息，
请参考[存储](/zh-cn/docs/concepts/storage/)。

### Pod 联网    {#pod-networking}

每个 Pod 都在每个地址族中获得一个唯一的 IP 地址。
Pod 中的每个容器共享网络名字空间，包括 IP 地址和网络端口。
**Pod 内**的容器可以使用 `localhost` 互相通信。
当 Pod 中的容器与 **Pod 之外**的实体通信时，它们必须协调如何使用共享的网络资源（例如端口）。

在同一个 Pod 内，所有容器共享一个 IP 地址和端口空间，并且可以通过 `localhost` 发现对方。
他们也能通过如 SystemV 信号量或 POSIX 共享内存这类标准的进程间通信方式互相通信。
不同 Pod 中的容器的 IP 地址互不相同，如果没有特殊配置，就无法通过 OS 级 IPC 进行通信。
如果某容器希望与运行于其他 Pod 中的容器通信，可以通过 IP 联网的方式实现。

Pod 中的容器所看到的系统主机名与为 Pod 配置的 `name` 属性值相同。
[网络](/zh-cn/docs/concepts/cluster-administration/networking/)部分提供了更多有关此内容的信息。

## Pod 安全设置     {#pod-security}

要对 Pod 和容器设置安全约束，请使用 Pod 规约中的 `securityContext` 字段。
该字段使你可以精细控制 Pod 或单个容器可以执行的操作。
有关更多详细信息，请参阅 [Pod 高级配置](/zh-cn/docs/concepts/workloads/pods/advanced-pod-config/)。

对于基本安全配置，你应该满足 Baseline Pod 安全标准，并以非 root
用户身份运行容器。你可以设置简单的安全上下文：

```yaml
apiVersion: v1
kind: Pod
metadata:
  name: security-context-demo
spec:
  securityContext:
    runAsUser: 1000
    runAsGroup: 3000
    fsGroup: 2000
  containers:
  - name: sec-ctx-demo
    image: busybox
    command: ["sh", "-c", "sleep 1h"]
```

有关高级安全上下文配置（包括 capabilities、seccomp 配置文件和详细安全选项）的信息，
请参阅[安全概念](/zh-cn/docs/concepts/security/)部分。

* 要了解可以使用的内核级安全约束，请参阅
  [Pod 和容器的 Linux 内核安全约束](/zh-cn/docs/concepts/security/linux-kernel-security-constraints)。
* 要了解有关 Pod 安全上下文的更多信息，
  请参阅[为 Pod 或容器配置安全上下文](/zh-cn/docs/tasks/configure-pod-container/security-context/)。

## 资源请求和限制

配置 Pod 时，你可以选择性地指定容器所需的每种资源的数量。
最常指定的资源是 CPU 和内存（RAM）。

当你为 Pod 中的容器指定资源请求（requests）时，kube-scheduler
会使用此信息来决定将 Pod 部署在哪个节点上。

当你为容器指定资源限制（limits）时，kubelet
会强制执行这些限制，以确保正在运行的容器使用的资源量不会超过你设置的限制。

CPU 限制通过 CPU 节流机制来强制执行。
当容器接近其 CPU 限制时，内核会限制其对 CPU 的访问。
内存限制也通过内核强制执行，当容器超出其内存限制时，
内核会通过内存不足（OOM）机制终止进程。

设置 CPU 限制需要权衡利弊。
CPU 限制有助于防止“嘈杂邻居”问题，即同一节点上的单个工作负载会占用过多资源，
导致其他工作负载无法获得足够的资源的问题。这在多租户环境中尤为重要。
然而，即使节点有剩余的 CPU 资源，CPU 限制也可能导致性能下降，
从而降低对延迟敏感的工作负载的性能。
是否设置 CPU 限制取决于你的环境、工作负载特性和隔离要求。

有关资源单位、强制执行行为和配置示例的详细信息，请参阅
[Pod 和容器的资源管理](/zh-cn/docs/concepts/configuration/manage-resources-containers/)。

## 静态 Pod    {#static-pods}

**静态 Pod（Static Pod）** 直接由特定节点上的 `kubelet` 守护进程管理，
不需要 API 服务器看到它们。
尽管大多数 Pod 都是通过控制面（例如，Deployment）
来管理的，对于静态 Pod 而言，`kubelet` 直接监控每个 Pod，并在其失效时重启之。

静态 Pod 通常绑定到某个节点上的 kubelet。
其主要用途是运行自托管的控制面。在自托管场景中，使用 `kubelet`
来管理各个独立的[控制面组件](/zh-cn/docs/concepts/architecture/#control-plane-components)。

详情见[静态 Pod](/zh-cn/docs/concepts/workloads/pods/static-pods/)。

## 包含多个容器的 Pod   {#how-pods-manage-multiple-containers}

Pod 被设计成支持构造内聚的服务单元的多个协作进程（形式为容器）。
Pod 中的容器被自动并置到集群中的同一物理机或虚拟机上，并可以一起进行调度。
容器之间可以共享资源和依赖、彼此通信、协调何时以及何种方式终止自身。

Kubernetes 集群中的 Pod 主要有两种用法：

* **运行单个容器的 Pod**。"每个 Pod 一个容器"模型是最常见的 Kubernetes 用例；
  在这种情况下，可以将 Pod 看作单个容器的包装器。Kubernetes 直接管理 Pod，而不是容器。
* **运行多个需要协同工作的容器的 Pod**。
  Pod 可以封装由多个紧密耦合且需要共享资源的并置容器组成的应用。
  这些位于同一位置的容器可能形成单个内聚的服务单元 —— 一个容器将文件从共享卷提供给公众，
  而另一个单独的边车容器则刷新或更新这些文件。
  Pod 将这些容器和存储资源打包为一个可管理的实体。

例如，你可能有一个容器，为共享卷中的文件提供 Web 服务器支持，以及一个单独的
[边车（Sidecar）](/zh-cn/docs/concepts/workloads/pods/sidecar-containers/)
容器负责从远端更新这些文件，如下图所示：

有些 Pod 具有 Init 容器和
应用容器。
Init 容器默认会在启动应用容器之前运行并完成。

你还可以拥有为主应用 Pod
提供辅助服务的[边车容器](/zh-cn/docs/concepts/workloads/pods/sidecar-containers/)（例如：服务网格）。

启用 `SidecarContainers`
[特性门控](/zh-cn/docs/reference/command-line-tools-reference/feature-gates/)（默认启用）允许你为
Init 容器指定 `restartPolicy: Always`。
设置重启策略为 `Always` 会确保设置的容器被视为**边车**，
并在 Pod 的整个生命周期内保持运行。
你显式定义为边车容器的容器会在主应用 Pod 之前启动，并保持运行直至 Pod 关闭。

## 容器探针   {#container-probes}

**Probe** 是由 kubelet 对容器执行的定期诊断。要执行诊断，kubelet 可以执行三种动作：

- `ExecAction`（借助容器运行时执行）
- `TCPSocketAction`（由 kubelet 直接检测）
- `HTTPGetAction`（由 kubelet 直接检测）

你可以参阅 Pod 的生命周期文档中的[探针](/zh-cn/docs/concepts/workloads/pods/pod-lifecycle/#container-probes)部分。

## 

* 了解 [Pod 生命周期](/zh-cn/docs/concepts/workloads/pods/pod-lifecycle/)。
* 了解 [PodDisruptionBudget](/zh-cn/docs/concepts/workloads/pods/disruptions/)，
  以及你可以如何利用它在出现干扰因素时管理应用的可用性。
* Pod 在 Kubernetes REST API 中是一个顶层资源。
  
  对象的定义中包含了更多的细节信息。
* 博客[分布式系统工具箱：复合容器模式](/blog/2015/06/the-distributed-system-toolkit-patterns/)中解释了在同一
  Pod 中包含多个容器时的几种常见布局。
* 了解 [Pod 拓扑分布约束](/zh-cn/docs/concepts/scheduling-eviction/topology-spread-constraints/)。
* 阅读 [Pod 高级配置](/zh-cn/docs/concepts/workloads/pods/advanced-pod-config/)以详细了解该主题。
  此页面涵盖了 Pod 配置的更多方面，包括：
  * PriorityClasses（优先级类）
  * RuntimeClasses（运行时类）
  * 配置**调度**的高级方法：Kubernetes 决定 Pod 应在哪个节点上运行的方式。

要了解为什么 Kubernetes 会在其他资源
（如 StatefulSet
或 Deployment）
封装通用的 Pod API，相关的背景信息可以在前人的研究中找到。具体包括：

* [Aurora](https://aurora.apache.org/documentation/latest/reference/configuration/#job-schema)
* [Borg](https://research.google/pubs/large-scale-cluster-management-at-google-with-borg/)
* [Marathon](https://github.com/d2iq-archive/marathon)
* [Omega](https://research.google/pubs/pub41684/)
* [Tupperware](https://engineering.fb.com/data-center-engineering/tupperware/)。
