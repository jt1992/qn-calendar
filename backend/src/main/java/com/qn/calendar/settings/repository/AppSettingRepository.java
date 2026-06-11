package com.qn.calendar.settings.repository;

import com.qn.calendar.settings.entity.AppSetting;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AppSettingRepository extends JpaRepository<AppSetting, Long> {
}
