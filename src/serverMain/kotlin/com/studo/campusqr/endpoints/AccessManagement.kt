package com.studo.campusqr.endpoints

import com.studo.campusqr.common.*
import com.studo.campusqr.database.BackendAccess
import com.studo.campusqr.database.BackendLocation
import com.studo.campusqr.database.DateRange
import com.studo.campusqr.extensions.*
import com.studo.campusqr.utils.AuthenticatedApplicationCall
import com.studo.katerbase.MongoMainEntry.Companion.randomId
import com.studo.katerbase.any
import com.studo.katerbase.equal
import com.studo.katerbase.greater
import com.studo.katerbase.inArray
import java.util.*

suspend fun getAccess(id: String): BackendAccess? = runOnDb {
  getCollection<BackendAccess>().findOne(BackendAccess::_id equal id)
}

private fun BackendAccess.toClientClass(location: BackendLocation) = ClientAccessManagement(
  id = _id,
  locationName = location.name,
  locationId = location._id,
  allowedEmails = allowedEmails.toTypedArray(),
  dateRanges = dateRanges.map { it.toClientClass("") }.toTypedArray(), // TODO @zs
  note = note,
  reason = reason
)

private suspend fun getLocationsMap(ids: List<String>): Map<String, BackendLocation> = runOnDb {
  getCollection<BackendLocation>()
    .find(BackendLocation::_id inArray ids)
    .associateBy { it._id }
}

private val AuthenticatedApplicationCall.isAllowed get() = user.isModerator || user.type == UserType.ACCESS_MANAGER

suspend fun AuthenticatedApplicationCall.listAccess() {
  if (!isAllowed) {
    respondForbidden(); return
  }

  val locationId = parameters["locationId"]

  val accessPayloads: List<BackendAccess> = runOnDb {
    with(getCollection<BackendAccess>()) {
      when {
        locationId != null && (user.isModerator) -> {
          find(BackendAccess::locationId equal locationId).toList()
        }
        else -> {
          find(BackendAccess::createdBy equal user._id).toList()
        }
      }
    }
  }

  val locations = getLocationsMap(if (locationId != null) listOf(locationId) else accessPayloads.map { it.locationId })
  val accessManagement = accessPayloads.map { it.toClientClass(locations.getValue(it.locationId)) }

  respondObject(
    AccessManagementData(
      accessManagement = accessManagement.toTypedArray(),
      clientLocation = if (locationId != null) locations.values.firstOrNull()?.toClientClass(language) else null
    )
  )
}

suspend fun AuthenticatedApplicationCall.listExportAccess() {
  if (!isAllowed) {
    respondForbidden(); return
  }

  val locationId = parameters["locationId"]
  val now = Date()

  val accessPayloads: List<BackendAccess> = runOnDb {
    with(getCollection<BackendAccess>()) {
      when {
        locationId != null && (user.isModerator) -> {
          find(
            BackendAccess::locationId equal locationId,
            BackendAccess::dateRanges.any(DateRange::to greater now)
          ).toList()
        }
        else -> {
          find(
            BackendAccess::createdBy equal user._id,
            BackendAccess::dateRanges.any(DateRange::to greater now)
          ).toList()
        }
      }
    }
  }

  val location = if (locationId != null) getLocationsMap(listOf(locationId)).values.firstOrNull() else null

  val permits = accessPayloads
    .flatMap { access ->
      access.dateRanges
        .filter { it.to > now } // A BackendAccess can have multiple dateRanges, and at least one is (by the query) not in the past
        .flatMap { dateRange ->
          access.allowedEmails.map { allowedEmail ->
            AccessManagementExportData.Permit(dateRange.toClientClass(), allowedEmail)
          }
        }
    }
    .sortedBy { it.dateRange.to } // Shorter timeslots on top
    .sortedBy { it.dateRange.from }

  respondObject(
    AccessManagementExportData(
      permits = permits.toTypedArray(),
      clientLocation = location?.toClientClass(language)
    )
  )
}

suspend fun AuthenticatedApplicationCall.getAccess() {
  if (!isAllowed) {
    respondForbidden(); return
  }

  val accessId = parameters["id"]!!

  val access = getAccess(accessId) ?: throw IllegalArgumentException("Access doesn't exist")
  val location = getLocationsMap(listOf(access.locationId)).getValue(access.locationId)

  respondObject(access.toClientClass(location))
}

suspend fun AuthenticatedApplicationCall.createAccess() {
  if (!isAllowed) {
    respondForbidden(); return
  }

  val newAccessPayload: NewAccess = receiveClientPayload()

  val newAccess = BackendAccess().apply {
    _id = randomId()
    createdBy = user._id
    createdDate = Date()
    locationId = newAccessPayload.locationId
    allowedEmails = newAccessPayload.allowedEmails.toList()
    dateRanges = newAccessPayload.dateRanges.map { DateRange(it) }
    note = newAccessPayload.note
    reason = newAccessPayload.reason
  }

  runOnDb {
    getCollection<BackendAccess>().insertOne(newAccess, upsert = false)
  }

  respondOk()
}

suspend fun AuthenticatedApplicationCall.deleteAccess() {
  if (!isAllowed) {
    respondForbidden(); return
  }

  val accessId = parameters["id"]!!

  val access = getAccess(accessId) ?: throw IllegalArgumentException("Access doesn't exist")

  if (user._id != access.createdBy && !user.isModerator) {
    respondForbidden(); return
  }

  runOnDb {
    getCollection<BackendAccess>().deleteOne(BackendAccess::_id equal access._id)
  }

  respondOk()
}

suspend fun AuthenticatedApplicationCall.duplicateAccess() {
  if (!isAllowed) {
    respondForbidden(); return
  }

  val accessId = parameters["id"]!!

  val access = getAccess(accessId) ?: throw IllegalArgumentException("Access doesn't exist")
  access._id = randomId()

  runOnDb {
    getCollection<BackendAccess>().insertOne(access, upsert = false)
  }

  respondOk()
}

suspend fun AuthenticatedApplicationCall.editAccess() {
  if (!isAllowed) {
    respondForbidden(); return
  }

  val accessId = parameters["id"]!!
  val access = getAccess(accessId) ?: throw IllegalArgumentException("Access doesn't exist")

  if (user._id != access.createdBy && !user.isModerator) {
    respondForbidden(); return
  }

  val editAccessPayload: EditAccess = receiveClientPayload()

  runOnDb {
    getCollection<BackendAccess>().updateOne(BackendAccess::_id equal accessId) {
      with(editAccessPayload) {
        if (locationId != null) {
          BackendAccess::locationId setTo locationId
        }
        if (allowedEmails != null) {
          BackendAccess::allowedEmails setTo allowedEmails.toList()
        }
        if (dateRanges != null) {
          BackendAccess::dateRanges setTo dateRanges.map { DateRange(it) }
        }
        if (note != null) {
          BackendAccess::note setTo note
        }
        if (reason != null) {
          BackendAccess::reason setTo reason
        }
      }
    }
  }

  respondOk()
}