//! JNI uses an ID registry, never a Java-provided raw pointer. Exceptions and
//! Rust panics are converted to Java exceptions before crossing the ABI.
use crate::Session;
use anyhow::{Context, Result, ensure};
use jni::JNIEnv;
use jni::objects::{JClass, JFloatArray, JString};
use jni::sys::{jboolean, jfloatArray, jint, jlong, jstring};
use std::collections::HashMap;
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::sync::atomic::{AtomicI64, Ordering};
use std::sync::{LazyLock, Mutex};

static SESSIONS: LazyLock<Mutex<HashMap<i64, Session>>> = LazyLock::new(|| Mutex::new(HashMap::new()));
static NEXT_ID: AtomicI64 = AtomicI64::new(1);

fn boundary<T: Default>(env: &mut JNIEnv<'_>, f: impl FnOnce(&mut JNIEnv<'_>) -> Result<T>) -> T {
    let result = catch_unwind(AssertUnwindSafe(|| f(env)));
    match result {
        Ok(Ok(value)) => value,
        error => {
            let message = match error {
                Ok(Err(error)) => format!("Mortal native: {error:#}"),
                _ => "Mortal native rejected inconsistent game state; resynchronize the game".to_owned(),
            };
            let _ = env.throw_new("java/lang/IllegalStateException", message);
            T::default()
        }
    }
}

fn insert(session: Session) -> Result<i64> {
    let id = NEXT_ID.fetch_add(1, Ordering::Relaxed);
    ensure!(id > 0, "native session ID exhausted");
    SESSIONS.lock().unwrap_or_else(|e| e.into_inner()).insert(id, session);
    Ok(id)
}

fn with_session<T>(id: i64, f: impl FnOnce(&mut Session) -> Result<T>) -> Result<T> {
    let mut sessions = SESSIONS.lock().unwrap_or_else(|e| e.into_inner());
    f(sessions.get_mut(&id).context("native session is closed")?)
}

fn floats(env: &JNIEnv<'_>, array: &JFloatArray<'_>) -> Result<Vec<f32>> {
    let len = env.get_array_length(array)?;
    ensure!(len <= 46, "model output is too large");
    let mut output = vec![0.0; len as usize];
    env.get_float_array_region(array, 0, &mut output)?;
    Ok(output)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_akagi_mobile_engine_NativeMortal_create(mut env: JNIEnv, _: JClass, player: jint, players: jint) -> jlong {
    boundary(&mut env, |_| {
        ensure!((0..4).contains(&player) && (3..=4).contains(&players), "invalid player count or seat");
        insert(Session::new(player as u8, players as u8)?)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_akagi_mobile_engine_NativeMortal_destroy(mut env: JNIEnv, _: JClass, id: jlong) {
    boundary(&mut env, |_| {
        SESSIONS.lock().unwrap_or_else(|e| e.into_inner()).remove(&id);
        Ok(())
    });
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_akagi_mobile_engine_NativeMortal_accept(mut env: JNIEnv, _: JClass, id: jlong, json: JString) -> jstring {
    boundary(&mut env, |env| {
        let line: String = env.get_string(&json)?.into();
        let encoded = with_session(id, |s| Ok(serde_json::to_string(&s.accept(&line)?)?))?;
        Ok(env.new_string(encoded)?.into_raw())
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_akagi_mobile_engine_NativeMortal_observation(mut env: JNIEnv, _: JClass, id: jlong, kan: jboolean) -> jfloatArray {
    boundary(&mut env, |env| {
        let values = with_session(id, |s| Ok(s.encoding(kan != 0)?.obs.clone()))?;
        let array = env.new_float_array(values.len() as jint)?;
        env.set_float_array_region(&array, 0, &values)?;
        Ok(array.into_raw())
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_akagi_mobile_engine_NativeMortal_resolve(mut env: JNIEnv, _: JClass, id: jlong, scores: JFloatArray, kan_scores: JFloatArray) -> jstring {
    boundary(&mut env, |env| {
        let scores = floats(env, &scores)?;
        let kan_scores = floats(env, &kan_scores)?;
        let result = with_session(id, |s| Ok(serde_json::to_string(&s.resolve(&scores, &kan_scores)?)?))?;
        Ok(env.new_string(result)?.into_raw())
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_akagi_mobile_engine_NativeMortal_forkReach(mut env: JNIEnv, _: JClass, id: jlong) -> jlong {
    boundary(&mut env, |_| {
        let fork = with_session(id, |s| s.fork_reach())?;
        insert(fork)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_akagi_mobile_engine_NativeMortal_snapshot(mut env: JNIEnv, _: JClass, id: jlong) -> jstring {
    boundary(&mut env, |env| {
        let result = with_session(id, |s| Ok(serde_json::to_string(&s.snapshot())?))?;
        Ok(env.new_string(result)?.into_raw())
    })
}
