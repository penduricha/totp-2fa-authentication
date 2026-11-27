package com.example.be_web_admin.repository.redis;

import com.example.be_web_admin.model.redis.LoginChallenge;
import org.springframework.data.repository.CrudRepository;

public interface LoginChallengeRepository extends CrudRepository<LoginChallenge, String> {
}
