package com.car.ipc;
import com.car.ipc.ICallback;
// Deliberate subset: vendor transaction 1 (cmd) and 2 (get) are not exposed here.
interface IRemote {
    oneway void register(in String[] ids, ICallback callback, boolean immediateSnapshot) = 2;
    oneway void unregister(in String[] ids, ICallback callback) = 3;
}
