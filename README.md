# RxLeakCheck
Android library for detecting leaks related to RxJava 1.x

      TL;DR - call `RxLeakCheck.initialize` at the end of application startup, and
      then call `RxLeakCheck.dumpLeaks` during graceful shutdown. Any calls to
      Observable.subscribe() that were not properly matched with an .unsubscribe()
      call, and whose source Observable has not terminated, will be dumped to
      the debug log as a leak.


## Background

 Whenever application code subscribes to an Observable with Observable.subscribe(),
 they receive a Subscription object in return. In general, application code should
 invoke unsubscribe() on this this Subscription object in order to free up resources
 when they are no longer interested in the source Observable.
 
 Failing to call unsubscribe() can cause significant resource leaks, as it is often
 the case that Observable.subscribe() is called with lambdas or other constructs
 that hold strong references to objects such as Fragments, Activities, and other
 objects that would otherwise be released.

 Subscriptions are effectively unsubscribed, automatically, when the source
 Observable terminates (completes with OnComplete, or emits OnError). And so,
 in practice, the Subscription object returned from .subscribe is often ignore by
 application code. This is done when the application programmer knows, believes, or
 contrives to ensure that the source Observable will terminate at a predictable
 time; for example, by using a timeout() or a bindToLifecycle() type of operation.

 So in order to ensure the Subscription object, and all its dependent resources, are
 properly freed, one of two things has to happen: either the programmer has to
 explicitly unsubscribe(), or the Observable has to terminate.

 RxLeakCheck uses RxHooks (provided by RxJava) to track Subscriptions and ensure that
 one of those two things happen. At the point dumpLeaks() is called, any Subscription
 that has not been unsubscribed in one of those two ways is considered a leaked
 Subscription, and RxLeakCheck will report the stacktrace at the point of the original
 subscribe.

## LICENSE

Copyright (c) 2017-present, Myk Willis & Company, LLC.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
